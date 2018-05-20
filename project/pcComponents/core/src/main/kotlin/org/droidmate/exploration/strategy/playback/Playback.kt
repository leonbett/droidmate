// DroidMate, an automated execution generator for Android apps.
// Copyright (C) 2012-2018. Saarland University
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
// Current Maintainers:
// Nataniel Borges Jr. <nataniel dot borges at cispa dot saarland>
// Jenny Hotzkow <jenny dot hotzkow at cispa dot saarland>
//
// Former Maintainers:
// Konrad Jamrozik <jamrozik at st dot cs dot uni-saarland dot de>
//
// web: www.droidmate.org
package org.droidmate.exploration.strategy.playback

import kotlinx.coroutines.experimental.runBlocking
import org.droidmate.exploration.ExplorationContext
import org.droidmate.exploration.actions.*
import org.droidmate.exploration.actions.AbstractExplorationAction.Companion.getActionIdentifier
import org.droidmate.exploration.statemodel.*
import org.droidmate.exploration.statemodel.ModelConfig
import org.droidmate.exploration.statemodel.Model
import org.droidmate.exploration.statemodel.features.ActionPlaybackFeature
import org.droidmate.exploration.strategy.widget.Explore
import java.lang.Integer.max
import java.nio.file.Path

@Suppress("unused")
open class Playback constructor(private val modelDir: Path) : Explore() {

	private var traceIdx = 0
	private var actionIdx = 0
	private lateinit var model : Model
	private var lastSkipped: ActionData = ActionData.empty

	private val watcher: ActionPlaybackFeature by lazy {
		(context.watcher.find { it is ActionPlaybackFeature }
				?: ActionPlaybackFeature(model)
						.also { context.watcher.add(it) }) as ActionPlaybackFeature
	}

	override fun initialize(memory: ExplorationContext) {
		super.initialize(memory)

		model = ModelLoader.loadModel(ModelConfig(modelDir, context.apk.packageName, true))
	}

	private fun isComplete(): Boolean {
		return model.getPaths().let { traceIdx+1 == it.size && actionIdx+1 == it[traceIdx].size }
	}

	private fun supposedToBeCrash():Boolean{
		val lastAction = model.getPaths()[traceIdx].getActions()[max(0,actionIdx)]
		return runBlocking { model.getState(lastAction.resState)!!.isAppHasStoppedDialogBox }
	}

	private fun getNextTraceAction(peek: Boolean = false): ActionData {
		model.let {
			it.getPaths()[traceIdx].let { currentTrace ->
				if (currentTrace.size - 1 == actionIdx) { // check if all actions of this trace were handled
					if(it.getPaths().size == traceIdx + 1) return ActionData.empty  // this may happen on a peek for next action on the end of the trace
					return it.getPaths()[traceIdx + 1].first().also {
						if (!peek) {
							traceIdx += 1
							actionIdx = 0
						}
					}
				}
				return currentTrace.getActions()[actionIdx].also {
					if (!peek)
						actionIdx += 1
				}
			}
		}
	}


	/** determine if the state is similar enough to execute a back action by computing how many relevant widgets are similar */
	private fun StateData.similarity(other: StateData): Double {
		val otherWidgets = other.widgets
		val candidates = this.widgets.filter{this.isRelevantForId(it)}
		val mappedWidgets = candidates.map { w ->
			if (otherWidgets.any { it.uid == w.uid || it.propertyConfigId == w.propertyConfigId })
				1
			else
				0
		}
		return mappedWidgets.sum() / candidates.size.toDouble()
	}

	/** checking if we can actually trigger the widget of our recorded trace */
	private fun Widget?.canExecute(context: StateData): Pair<Double,Widget?> {
		return when{
			this == null -> Pair(0.0, null) // no match possible
			context.widgets.any { it.id == this.id } -> Pair(1.0, this) // we have a perfect match
			else -> // possibly it is a match but we can't be 100% sure
				context.widgets.find { it.canBeActedUpon && it.uid == this.uid }	?.let { Pair(0.6, it) } // prefer uid match over property equivalence
						?: context.widgets.find { it.canBeActedUpon && it.propertyConfigId == this.propertyConfigId }?.let{ Pair(0.5, it) }
						?:	Pair(0.0, null) // no match found
		}
	}

	private fun getNextAction(): AbstractExplorationAction {

		// All traces completed. Finish
		if (isComplete())
			return TerminateExplorationAction()

		val currTraceData = getNextTraceAction()
		val action = currTraceData.actionType
		return when (action) {
			getActionIdentifier<ClickExplorationAction>() -> {
				val verifyExecutability = currTraceData.targetWidget.canExecute(context.getCurrentState())
				if(verifyExecutability.first>0.0) {
					PlaybackExplorationAction(verifyExecutability.second!!, "[${verifyExecutability.first}]")
				}

				// not found, go to the next or try to repeat previous action depending on what is matching better
				else {
					watcher.addNonReplayableActions(traceIdx, actionIdx)
					val prevEquiv = lastSkipped.targetWidget.canExecute(context.getCurrentState())  // check if the last skipped action may be appyable now
					val peekAction = getNextTraceAction(peek = true)
					val nextEquiv = peekAction.targetWidget.canExecute(context.getCurrentState())
					val abstractExplorationAction = if (prevEquiv.first > nextEquiv.first  // try to execute the last previously skipped action only if the next action is executable afterwards
							&& runBlocking {
								model.getState(lastSkipped.resState)?.run {
									actionableWidgets.any {
										peekAction.targetWidget == null || it.uid == peekAction.targetWidget.uid
												|| it.propertyConfigId == peekAction.targetWidget.uid
									}
								}
							} == true) {
						lastSkipped = ActionData.empty  // we execute it now so do not try to do so again
						PlaybackExplorationAction(prevEquiv.second!!, "[previously skipped]")
					} else {
						lastSkipped = currTraceData
						println("[skip action ($traceIdx,$actionIdx)] $lastSkipped")
						getNextAction()
					}
					abstractExplorationAction
				}
			}
			getActionIdentifier<TerminateExplorationAction>() -> {
				PlaybackTerminateAction()
			}
			getActionIdentifier<ResetAppExplorationAction>() -> {
				PlaybackResetAction()
			}
			getActionIdentifier<PressBackExplorationAction>() -> {
				// If already in home screen, ignore
				if (context.getCurrentState().isHomeScreen) {
					watcher.addNonReplayableActions(traceIdx, actionIdx)
					return getNextAction()
				}

				val similarity = context.getCurrentState().similarity(runBlocking { model.getState(currTraceData.resState)!!})

				// Rule:
				// 0 - Doesn't belong to app, skip
				// 1 - Same screen, press back
				// 2 - Not same screen and can execute next widget action, stay
				// 3 - Not same screen and can't execute next widget action, press back
				// Known issues: multiple press back / reset in a row

				if (similarity >= 0.95) {
					PlaybackPressBackAction()
				} else {
					val nextTraceData = getNextTraceAction(peek = true)

						val nextWidget = nextTraceData.targetWidget

						if (nextWidget.canExecute(context.getCurrentState()).first>0.0) {
							watcher.addNonReplayableActions(traceIdx, actionIdx)
							getNextAction()
						}

					PlaybackPressBackAction()
				}
			}
			else -> {
				ClickExplorationAction(currTraceData.targetWidget!!, useCoordinates = true)
			}
		}
	}

	/*fun getExplorationRatio(widget: Widget? = null): Double {
		TODO()
//		val totalSize = traces.map { it.getSize(widget) }.sum()
//
//		return traces
//				.map { trace -> trace.getExploredRatio(widget) * (trace.getSize(widget) / totalSize.toDouble()) }
//				.sum()
	}*/

	override fun internalDecide(): AbstractExplorationAction {
		val allWidgetsBlackListed = this.updateState()  //FIXME this function does not work anymore use the Blacklist or Crashlist Model Features instead
		if (allWidgetsBlackListed)
			this.notifyAllWidgetsBlacklisted()

		return chooseAction()
	}

	/** reset the []actionIdx] to the position of the last reset or 0 if none exists
	 * (action 0 should always be a reset to start the app) */
	private fun handleReplayCrash(){
		logger.info("handle app crash on replay")
		model.getPaths()[traceIdx].let { trace ->
			var isReset = false
			var i = actionIdx
			while (!isReset && i >= 0) {
				i -= 1
				isReset = trace.getActions()[i].actionType != getActionIdentifier<ResetAppExplorationAction>()
			}
		}
	}

	override fun chooseAction(): AbstractExplorationAction {
		if( !context.isEmpty() && context.getCurrentState().isAppHasStoppedDialogBox && ! supposedToBeCrash()
			&& getNextTraceAction(peek = true).actionType != getActionIdentifier<ResetAppExplorationAction>())	handleReplayCrash()

		return getNextAction().also{ println("PLAYBACK: $it")}
	}
}