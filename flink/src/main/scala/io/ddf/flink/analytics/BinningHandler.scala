package io.ddf.flink.analytics

import java.text.DecimalFormat
import java.{lang, util}

import io.ddf.DDF
import io.ddf.analytics.ABinningHandler.BinningType
import io.ddf.analytics.AStatisticsSupporter.HistogramBin
import io.ddf.analytics.{AStatisticsSupporter, ABinningHandler, IHandleBinning}
import io.ddf.exception.DDFException
import io.ddf.flink.FlinkDDFManager
import io.ddf.flink.utils.Misc
import io.ddf.flink.utils.Misc.isNull

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.JavaConversions._

import org.apache.flink.api.scala._

class BinningHandler(ddf: DDF) extends ABinningHandler(ddf) with IHandleBinning {

  override def binningImpl(column: String, binningTypeString: String, numBins: Int, inputBreaks: Array[Double], includeLowest: Boolean,
                           right: Boolean): DDF = {

    val colMeta = ddf.getColumn(column)

    val binningType = BinningType.get(binningTypeString)

    this.breaks = inputBreaks

    binningType match {
      case BinningType.CUSTOM =>
        if (isNull(breaks)) {
          throw new DDFException("Please enter valid break points")
        }
        if (breaks.sorted.deep != breaks.deep) {
          throw new DDFException("Please enter increasing breaks")
        }
      case BinningType.EQUAlFREQ => breaks = {
        if (numBins < 2) {
          throw new DDFException("Number of bins cannot be smaller than 2")
        }
        getQuantilesFromNumBins(colMeta.getName, numBins)
      }
      case BinningType.EQUALINTERVAL => breaks = {
        if (numBins < 2) {
          throw new DDFException("Number of bins cannot be smaller than 2")
        }
        getIntervalsFromNumBins(colMeta.getName, numBins).map(_.doubleValue())
      }
      case _ => throw new DDFException(String.format("Binning type %s is not supported", binningTypeString))
    }
    var intervals = createIntervals(breaks, includeLowest, right)

    breaks = breaks.sorted
    //remove single quote in intervals
    intervals = intervals.map(x => x.replace("'", ""))
    val newDDF = Misc.getBinned(ddf, breaks, column, intervals, includeLowest, right)
    newDDF.getSchemaHandler.setAsFactor(column).setLevels(intervals.toList.asJava)
    newDDF
  }

  def createIntervals(breaks: Array[Double], includeLowest: Boolean, right: Boolean): Array[String] = {
    val decimalPlaces: Int = 2
    val formatter = new DecimalFormat("#." + Iterator.fill(decimalPlaces)("#").mkString(""))
    val intervals: Array[String] = (0 to breaks.length - 2).map {
      i =>
        if (includeLowest && i == 0) {
          if (right) {
            "'[%s,%s]'".format(formatter.format(breaks(i)), formatter.format(breaks(i + 1)))
          } else {
            "'[%s,%s)'".format(formatter.format(breaks(i)), formatter.format(breaks(i + 1)))
          }
        } else if (right) {
          "'(%s,%s]'".format(formatter.format(breaks(i)), formatter.format(breaks(i + 1)))
        } else {
          "'(%s,%s)'".format(formatter.format(breaks(i)), formatter.format(breaks(i + 1)))
        }
    }.toArray

    if (includeLowest) {
      if (right) {
        intervals(0) = "'[%s,%s]'".format(formatter.format(breaks(0)), formatter.format(breaks(1)))
      } else {
        intervals(intervals.length - 1) =
          "'[%s,%s)'".format(formatter.format(breaks(breaks.length - 2)), formatter.format(breaks(breaks.length - 1)))
      }
    }
    mLog.info("interval labels = {}", intervals)
    intervals
  }


  def getIntervalsFromNumBins(colName: String, bins: Int): Array[Double] = {
    val res = ddf.getStatisticsSupporter.getVectorQuantiles(colName, Array(0.00001, 0.99999))
    val (min, max) = (res(0), res(1))
    val eachInterval = (max - min) / bins
    val probs: Array[Double] = Array.fill[Double](bins + 1)(0d)
    var i = 0
    while (i < bins + 1) {
      probs(i) = min + i * eachInterval
      i += 1
    }
    probs(bins) = max
    probs
  }

  def getQuantilesFromNumBins(colName: String, bins: Int): Array[Double] = {
    val eachInterval = 1.0 / bins
    val probs: Array[Double] = Array.fill[Double](bins + 1)(0.0)
    probs(0) = 0.00001
    var i = 1
    while (i < bins - 1) {
      probs(i) = (i + 1) * eachInterval
      i += 1
    }
    probs(bins) = 0.99999
    getQuantiles(colName, probs)
  }

  /**
   * Using hive UDF to get percentiles as breaks
   *
   */
  def getQuantiles(colName: String, pArray: Array[Double]): Array[Double] = {
    ddf.getStatisticsSupporter.getVectorQuantiles(colName, pArray.map {
      i =>
        val value: lang.Double = i
        value
    }).map(_.doubleValue())
  }

  val MAX_LEVEL_SIZE = Integer.parseInt(System.getProperty("factor.max.level.size", "1024"))

  /* Class to produce intervals from array of stopping
   * and method findInterval(Double) return an interval for a given Double
   */

  class Intervals(val stopping: List[Double],
                  private val includeLowest: Boolean = false,
                  right: Boolean = true,
                  formatter: DecimalFormat) extends Serializable {
    val intervals = createIntervals(Array[(Double => Boolean, String)](), stopping, first = true)
    // scalastyle:off cyclomatic.complexity
    @tailrec
    private def createIntervals(result: Array[(Double => Boolean, String)],
                                stopping: List[Double],
                                first: Boolean = false): Array[(Double => Boolean, String)] = stopping match {
      case Nil => result
      case x :: Nil => result
      case x :: y :: xs =>
        val bound: String = s"${formatter.format(x)},${formatter.format(y)}"
        if (includeLowest && right) {
          if (first) {
            createIntervals(result :+((z: Double) => z >= x && z <= y, s"[$bound]"), y :: xs)
          } else {
            createIntervals(result :+((z: Double) => z > x && z <= y, s"($bound]"), y :: xs)
          }
        } else if (includeLowest && !right) {
          if (xs == Nil) {
            createIntervals(result :+((z: Double) => z >= x && z <= y, s"[$bound]"), y :: xs)
          } else {
            createIntervals(result :+((z: Double) => z >= x && z < y, s"[$bound)"), y :: xs)
          }
        } else if (!includeLowest && right) {
          createIntervals(result :+((z: Double) => z > x && z <= y, s"($bound]"), y :: xs)
        } else {
          createIntervals(result :+((z: Double) => z >= x && z < y, s"[$bound)"), y :: xs)
        }
    }
    // scalastyle:on cyclomatic.complexity

    def findInterval(aNum: Double): Option[String] = {
      intervals.find {
        case (f, y) => f(aNum)
      } match {
        case Some((x, y)) => Option(y)
        case None => None
      }
    }
  }

  override def getVectorHistogram(column: String, numBins: Int): util.List[HistogramBin] = {
    val columnData = Misc.getDoubleColumn(ddf, column).get
    val manager = this.getManager.asInstanceOf[FlinkDDFManager]

    val max = columnData.reduce(_ max _).collect().head
    val min = columnData.reduce(_ min _).collect().head

    // Scala's built-in range has issues. See #SI-8782
    def customRange(min: Double, max: Double, steps: Int): IndexedSeq[Double] = {
      val span = max - min
      Range.Int(0, steps - 1, 1).map(s => min + (s * span) / steps) :+ max
    }

    // Compute the minimum and the maximum
    if (min.isNaN || max.isNaN || max.isInfinity || min.isInfinity) {
      throw new UnsupportedOperationException(
        "Histogram on either an empty DataSet or DataSet containing +/-infinity or NaN")
    }
    val range = if (min != max) {
      // Range.Double.inclusive(min, max, increment)
      // The above code doesn't always work. See Scala bug #SI-8782.
      // https://issues.scala-lang.org/browse/SI-8782
      customRange(min, max, numBins)
    } else {
      List(min, min)
    }

    val buckets: Array[java.lang.Double] = range.toArray.map(i => Double.box(i))
    val bins: util.List[HistogramBin] = new util.ArrayList[HistogramBin]()
    val histograms = Misc.collectHistogram(manager.getExecutionEnvironment, columnData, buckets)
    histograms.foreach { entry =>
      val bin: AStatisticsSupporter.HistogramBin = new AStatisticsSupporter.HistogramBin
      bin.setX(entry._1)
      bin.setY(entry._2.toDouble)
      bins.add(bin)
    }
    bins
  }

  override def getVectorApproxHistogram(column: String, numBins: Int): util.List[HistogramBin] = {
    getVectorHistogram(column, numBins)
  }
}
