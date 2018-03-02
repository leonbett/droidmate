// DroidMate, an automated execution generator for Android apps.
// Copyright (C) 2012-2018 Konrad Jamrozik
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
// email: jamrozik@st.cs.uni-saarland.de
// web: www.droidmate.org
package org.droidmate.exploration.strategy.playback

import org.droidmate.device.datatypes.IWidget
import org.droidmate.exploration.actions.*
import org.droidmate.exploration.strategy.*
import org.droidmate.exploration.strategy.widget.AbstractWidgetStrategy
import org.droidmate.misc.isEquivalentIgnoreLocation
import org.droidmate.misc.uniqueString
import java.io.IOException
import java.nio.file.Path

open class MemoryPlayback private constructor() : AbstractWidgetStrategy() {

    private lateinit var packageName: String

    protected constructor(storedMemoryData: Memory) : this() {
        this.packageName = storedMemoryData.getApk().packageName
        this.initializeFromMemory(storedMemoryData)
    }

    protected constructor(packageName: String, newTraces: List<PlaybackTrace>) : this() {
        this.traces.addAll(newTraces)
        this.packageName = packageName
    }

    val traces: MutableList<PlaybackTrace> = ArrayList()

    private fun initializeFromMemory(storedMemoryData: Memory) {
        val memoryRecords = storedMemoryData.getRecords()
        val packageName = storedMemoryData.getApk().packageName

        // Create traces from memory records
        // Each trace starts with a reset
        // Last trace ends with terminate exploration
        for (i in 0 until memoryRecords.size) {
            val memoryRecord = memoryRecords[i]

            if (memoryRecord.type == ExplorationType.Reset)
                traces.add(PlaybackTrace())

            val widgetContext = memory.getWidgetContext(memoryRecord.widgetContext.guiState, packageName)

            traces.last().add(memoryRecord.action, widgetContext)
        }
    }

    private fun isComplete(): Boolean {
        return traces.all { it.isComplete() }
    }

    private fun getNextTrace(): PlaybackTrace {
        return traces.first { !it.isComplete() }
    }

    private fun WidgetContext.similarity(other: WidgetContext): Double {
        val otherWidgets = other.widgetsInfo
        val mappedWidgets = this.widgetsInfo.map { w ->
            if (otherWidgets.any { it.widget.uniqueString == w.widget.uniqueString })
                1
            else
                0
        }
        return mappedWidgets.sum() / this.widgetsInfo.size.toDouble()
    }

    private fun IWidget.canExecute(context: WidgetContext, ignoreLocation: Boolean = false): Boolean {
        return if (ignoreLocation)
            (context.widgetsInfo.any { it.widget.isEquivalent(this) })
        else
            (!(this.text.isEmpty() && (this.resourceId.isEmpty()))) &&
                    (context.widgetsInfo.any { it.widget.isEquivalentIgnoreLocation(this) })
    }

    private fun getNextAction(context: WidgetContext): ExplorationAction {

        // All traces completed. Finish
        if (isComplete())
            return TerminateExplorationAction()

        val currTrace = getNextTrace()
        val currTraceData = currTrace.requestNext()
        val action = currTraceData.action
        when (action) {
            is WidgetExplorationAction -> {
                return if (action.widget.canExecute(context)) {
                    currTrace.explore(action)
                    PlaybackExplorationAction(action)
                    // not found, try ignoring the location if it has text and or resourceID
                } else if (action.widget.canExecute(context, true)) {
                    logger.warn("Same widget not found. Located similar (text and resourceID) widget in different position. Selecting it.")
                    currTrace.explore(action)
                    PlaybackExplorationAction(action)
                }
                // not found, go to the next
                else
                    getNextAction(context)
            }
            is TerminateExplorationAction -> {
                currTrace.explore(action)
                return PlaybackTerminateAction(action)
            }
            is ResetAppExplorationAction -> {
                currTrace.explore(action)
                return PlaybackResetAction(action)
            }
            is PressBackExplorationAction -> {
                // If already in home screen, ignore
                if (context.isHomeScreen())
                    return getNextAction(context)

                val similarity = context.similarity(currTraceData.widgetContext)

                // Rule:
                // 0 - Doesn't belong to app, skip
                // 1 - Same screen, press back
                // 2 - Not same screen and can execute next widget action, stay
                // 3 - Not same screen and can't execute next widget action, press back
                // Known issues: multiple press back / reset in a row

                return if (similarity == 1.0) {
                    currTrace.explore(action)
                    PlaybackPressBackAction(action)
                } else {
                    val nextTraceData = currTrace.peekNextWidgetAction()

                    if (nextTraceData != null) {
                        val nextWidgetAction = nextTraceData.action as WidgetExplorationAction
                        val nextWidget = nextWidgetAction.widget

                        if (nextWidget.canExecute(context, true))
                            getNextAction(context)
                    }

                    currTrace.explore(action)
                    PlaybackPressBackAction(action)
                }
            }
            else -> {
                currTrace.explore(action)
                return action
            }
        }
    }

    fun getExplorationRatio(widget: IWidget? = null): Double {
        val totalSize = traces.map { it.getSize(widget) }.sum()

        return traces
                .map { trace -> trace.getExploredRatio(widget) * (trace.getSize(widget) / totalSize.toDouble()) }
                .sum()
    }

    override val type: ExplorationType
        get() = ExplorationType.Playback

    override fun internalDecide(widgetContext: WidgetContext): ExplorationAction {
        val allWidgetsBlackListed = this.updateState(widgetContext)
        if (allWidgetsBlackListed)
            this.notifyAllWidgetsBlacklisted()

        return chooseAction(widgetContext)
    }

    override fun chooseAction(widgetContext: WidgetContext): ExplorationAction {
        return getNextAction(widgetContext)
    }

    /**
     * Returns a high priority only if the app is correct
     */
    override fun getFitness(widgetContext: WidgetContext): StrategyPriority {
        return StrategyPriority.PLAYBACK
    }

    // region Java overrides

    override fun toString(): String {
        return "${this.javaClass}\tApk: $packageName"
    }

    override fun equals(other: Any?): Boolean {
        if (other !is MemoryPlayback)
            return false

        return this.packageName == other.packageName
    }

    override fun hashCode(): Int {
        return this.traces.hashCode()
    }

    // endregion

    companion object {
        /**
         * Creates a new exploration strategy instance
         *
         * @param memoryFile Path to a memory file containing the recorded exploration(s)
         * @param packageName Package name which should be loaded from the memory. Load all APKS if not provided
         */
        fun build(memoryFile: Path, packageName: String = ""): List<ISelectableExplorationStrategy> {
            try {
                val memoryData = Memory.deserialize(memoryFile)

                return memoryData
                        .filter { packageName.isEmpty() || (it.getApk().packageName == packageName) }
                        .map { MemoryPlayback(it) }
            } catch (e: IOException) {
                logger.error(e.message, e)
                e.printStackTrace()
            }

            return ArrayList()
        }

        /**
         * Creates a new exploration strategy instance
         *
         * @param packageName Package name which should be loaded from the memory. Load all APKS if not provided
         * @param memoryTraces Trace of previous exploration (set of actions between 2 resets)
         */
        fun build(packageName: String, memoryTraces: List<PlaybackTrace>): List<ISelectableExplorationStrategy> {
            return listOf(MemoryPlayback(packageName, memoryTraces))
        }
    }
}