package org.droidmate.report

import com.google.common.collect.HashBasedTable
import com.google.common.collect.Table
import org.droidmate.exploration.data_aggregators.ExplorationOutput2
import org.droidmate.exploration.data_aggregators.IApkExplorationOutput2
import java.nio.file.Files
import java.nio.file.Path

class ExplorationOutput2Report(val output: ExplorationOutput2, val dir: Path) {

  fun report(): Unit {
    // KJA current work
    output.forEach {
      GUICoverageReportFile(it, dir).writeOut()
    }
  }
}

class GUICoverageReportFile(val data: IApkExplorationOutput2, val dir: Path) {

  val file = dir.resolve("${data.apk.fileName}_GUIReportFile.txt")

  fun writeOut() {

    GUICoverage(data).table().writeOut(file)
  }
}

fun <R, C, V> Table<R, C, V>.writeOut(file: Path) {
  val cellsString = this.cellSet().joinToString { it.toString() }
  Files.write(file, cellsString.toByteArray())
}

class GUICoverage(val data: IApkExplorationOutput2) {
  fun table(): Table<Int, Int, Int> {
    var table = HashBasedTable.create<Int, Int, Int>()
    table.put(0, 0, 10)
    table.put(1, 0, 15)
    table.put(2, 0, 15)
    return table
  }
}
