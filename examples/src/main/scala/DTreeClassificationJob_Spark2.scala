import io.hydrosphere.mist.lib._
import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.classification.DecisionTreeClassifier
import org.apache.spark.ml.feature.{IndexToString, StringIndexer, VectorIndexer}
import org.apache.spark.ml.linalg.{DenseVector, SparseVector, Vector, Vectors}

object DTreeClassificationJob extends MLMistJob with SQLSupport {
  def train(datasetPath: String, savePath: String): Map[String, Any] = {
    val data = session.read.format("libsvm").load(datasetPath)
    val Array(training, _) = data.randomSplit(Array(0.7, 0.3))
    val labelIndexer = new StringIndexer()
      .setInputCol("label")
      .setOutputCol("indexedLabel")
      .fit(data)
    val featureIndexer = new VectorIndexer()
      .setInputCol("features")
      .setOutputCol("indexedFeatures")
      .setMaxCategories(4)// features with > 4 distinct values are treated as continuous.
      .fit(data)
    val dt = new DecisionTreeClassifier()
      .setLabelCol("indexedLabel")
      .setFeaturesCol("indexedFeatures")

    val labelConverter = new IndexToString()
      .setInputCol("prediction")
      .setOutputCol("predictedLabel")
      .setLabels(labelIndexer.labels)

    val pipeline = new Pipeline()
      .setStages(Array(labelIndexer, featureIndexer, dt, labelConverter))

    val model = pipeline.fit(training)

    model.write.overwrite().save(savePath)
    Map.empty[String, Any]
}
  def serve(modelPath: String, features: List[List[Double]]): Map[String, Any] = {
    import io.hydrosphere.mist.ml.LocalPipelineModel._

    val pipeline = PipelineLoader.load(modelPath)
    val arrays = features map(_.toArray)
    val arrayOfVecs = arrays.map(Vectors.dense)
    val data = LocalData(
      LocalDataColumn("features", arrayOfVecs)
    )
    val result: LocalData = pipeline.transform(data)
    Map("result" -> result.select("predictedLabel").toMapList)
  }
}
