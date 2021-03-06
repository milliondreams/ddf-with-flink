package io.ddf.flink.content

import io.ddf.DDF
import io.ddf.content.AMetaDataHandler
import io.ddf.flink.FlinkDDF
import org.apache.flink.api.scala.DataSet

class MetaDataHandler(ddf: DDF) extends AMetaDataHandler(ddf) {
  override protected def getNumRowsImpl: Long = {
    val dataset: DataSet[Array[Object]] = ddf.getRepresentationHandler.get(classOf[DataSet[_]],classOf[Array[Object]]).asInstanceOf[DataSet[Array[Object]]]
    dataset.count()
  }
}
