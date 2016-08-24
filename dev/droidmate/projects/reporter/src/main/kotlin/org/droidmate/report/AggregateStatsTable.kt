// DroidMate, an automated execution generator for Android apps.
// Copyright (C) 2012-2016 Konrad Jamrozik
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
package org.droidmate.report

import com.google.common.collect.Table
import org.droidmate.exploration.actions.RunnableResetAppExplorationAction
import org.droidmate.exploration.data_aggregators.IApkExplorationOutput2

// KJA (reporting) produce table that can be readily imported to Excel that has columns:
// apk_name	run_time_in_seconds	actions#	in_that_resets# 
// actionable_views_seen# views_clicked_or_long_clicked_at_least_once# unique_apis# unique_event_apis# ANRs_seen# terminated_with_exception(give exception name: launch timeout, uninstall failure, other)
class AggregateStatsTable private constructor(val table: Table<Int, String, String>) : Table<Int, String, String> by table {

  constructor(data: List<IApkExplorationOutput2>) : this(AggregateStatsTable.build(data))
  
  companion object {
    val headerApkName = "file_name"
    val headerPackageName = "package_name"
    val headerExplorationTimeInSeconds = "exploration_seconds"
    val headerActionsCount = "actions"
    val headerResetActionsCount = "in_this_reset_actions"
    val headerViewsSeenCount = "actionable_unique_views_seen_at_least_once"
    val headerViewsClickedCount = "actionable_unique_views_clicked_or_long_clicked_at_least_once"

    fun build(data: List<IApkExplorationOutput2>): Table<Int, String, String> {

      return buildTable(
        headers = listOf(
          headerApkName,
          headerPackageName,
          headerExplorationTimeInSeconds,
          headerActionsCount,
          headerResetActionsCount,
          headerViewsSeenCount,
          headerViewsClickedCount
        ),
        rowCount = data.size,
        computeRow = { rowIndex ->
          val apkData = data[rowIndex]
          listOf(
            apkData.apk.fileName,
            apkData.packageName,
            apkData.explorationTimeInSeconds,
            apkData.actionsCount,
            apkData.resetActionsCount,
            //apkData.actRess.first().result.guiSnapshot
            "0", // KJA todo views seen. See ViewCountTable and DRY
            "0" // KJA todo views clicked. See ViewCountTable and DRY
          )
        }
      )
    }

    private val IApkExplorationOutput2.explorationTimeInSeconds: String
      get() = (explorationTimeInMs / 1000).toString()

    private val IApkExplorationOutput2.actionsCount: String
      get() = actions.size.toString()

    private val IApkExplorationOutput2.resetActionsCount: String
      get() = actions.filter { it is RunnableResetAppExplorationAction }.size.toString()

  }
}