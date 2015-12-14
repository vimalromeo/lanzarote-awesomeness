package com.barclays.adacore

import breeze.linalg.DenseVector
import com.barclays.adacore.utils.{TopElements, Logger, StatCounter}
import com.google.common.cache.AbstractCache.StatsCounter
import org.apache.spark.SparkContext
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.mllib.linalg.{Vectors, Vector}
import org.apache.spark.rdd.RDD._
import org.apache.spark.rdd.{PairRDDFunctions, RDD}
import it.tizianofagni.sparkboost.{DataUtils, BoostClassifier, MpBoostLearner, MultilabelPoint}

import scalaz.Scalaz._

case class MultilabelClassificationRecommender(@transient sc: SparkContext) extends RecommenderTrainer {

  def businessID(tx: AnonymizedRecord) = (tx.businessName, tx.businessTown)

  val merchantCodes =
    List("5051", "5044", "5462", "7523", "4784", "5814", "5441", "5994", "5122", "5451", "7922",
      "8651", "5973", "7832", "7395", "5499", "5912", "5947", "4112", "5192", "5812", "5251", "7933", "8699", "7929",
      "5942", "8398", "7251", "5921", "7997", "7991", "5813", "5131", "5943", "5735", "5937", "7841", "7216", "7210",
      "5111", "5411", "7999", "5970", "5995", "5422", "5993", "8661", "5310", "5169", "7932", "7393", "4814", "5399",
      "5983", "5722", "5811", "7998", "7996", "5541", "5331", "5651", "3741", "5714", "5699", "5261", "5621", "5977",
      "5099", "4812", "5542", "7941", "7993", "5691", "7911", "4011", "7221", "5992", "7296", "7032", "7211", "4582",
      "0763", "5193", "0780", "5999", "7230", "5311", "7992", "5039", "5998", "5945", "5641", "8049", "9399", "7333",
      "5137", "2791", "5949", "7338", "8641", "7342", "5940", "5697", "7298", "2741", "5211", "5065", "5655", "8041",
      "5661", "8031", "4215", "5139", "3514", "5950", "7542", "7297", "5309", "4131", "8211", "5200", "3604", "7829",
      "5941", "5198", "3246", "7995", "7994", "8071", "5631", "7011", "6011", "7629", "3405", "5611", "7379", "7399",
      "5719", "5231", "4899", "5971", "5948", "5072", "8249", "5931", "5046", "2842", "8220", "3501", "4121", "0742",
      "5935", "5996", "5045", "3615", "7394", "3672", "7372", "7622", "4900", "7692", "7277", "5533", "3692", "5300",
      "3502", "8021", "5094", "5733", "5085", "5199", "7033", "8911", "8299", "7311", "5698", "7361", "4457", "3509",
      "9402", "5734", "3681", "4411", "8050", "3637", "8351", "3750", "4816", "7631", "5932", "3811", "4214", "5944",
      "7392", "1731", "7349", "3381", "5976", "7534", "7299", "5946", "5532", "8043", "8099", "7623", "5732", "4225",
      "1771", "7339", "8011", "3504", "8999", "7641", "3503", "3755", "3351", "1520", "5047", "4468", "5074", "1761",
      "4111", "8062", "9222", "8042", "7699", "4789", "7512", "7549", "1799", "1711", "7535", "3779", "8244", "5013",
      "5933", "5713", "3015", "4511", "5551", "3395", "7513", "5021", "7531", "1750", "6300", "5712", "3008", "5571",
      "3051", "8931", "5972", "5718", "6051", "7217", "6010", "8111", "7276", "7538", "1740", "5561", "6513", "5975",
      "5599", "5271", "4829", "4722", "5592", "7261", "5511", "5521", "6012", "7519", "6211", "3441")
    .zipWithIndex.map(e => e._1 -> e._2.toDouble).toMap

  val occupationalID =
    List("1,9", "9,12", "1,10,12", "5,7,13", "1,5,10,11", "6,7", "1,4", "1,4,5,13", "5,9,10,12,13",
      "10,13,14", "1,7", "3,5,13", "9,14,15", "12,15", "1,12,15", "1,8,13", "3,13", "7,12", "12", "1,9,14", "9,12,15", "1,9,13",
      "1,13", "14", "5,6,9,11", "4", "1,12", "3,4,5,6", "8,12", "1,6", "8,14", "3,5,11", "1,2,7,9,11", "1,2,13,14", "4,11,13", "4,6",
      "2,5", "4,5", "5,7,15", "10", "6,11,12", "13", "2,3,15", "1,5,6", "3,7", "1,11", "1,2,13", "1,5,14", "", "3,6,7", "1,3,12",
      "1,5,13", "2,7", "3,12", "4,6,11", "1,4,6,14", "6", "1,3", "14,15", "2,15", "4,9,13", "7,9", "6,9", "3,10,11", "2,10", "10,15",
      "1,7,12,13", "3", "9,11", "4,12,15", "3,7,12,14", "7,12,13", "2", "1,10", "3,11", "1,8", "1,7,15", "7,8", "9,15", "2,8", "7,10",
      "2,4", "2,7,14,15", "5", "13,15", "4,8,10", "7,9,10,12", "7,8,9", "3,7,10", "6,11", "2,12,14", "1,10,14,15", "3,4,6,7,10",
      "10,11,13", "1,2", "2,6,7,8,9", "13,14", "7", "5,11", "1,4,15", "11", "6,10,11", "8", "12,13,14", "4,7", "3,14", "6,7,8,10,11",
      "4,7,13", "12,14", "3,7,8,11", "5,7,11", "1,7,8", "8,10", "2,4,7", "3,5,7,13", "8,13", "1,4,6,8,10,12", "9,11,15", "8,9,13,14",
      "4,6,8,14", "1,3,4,14", "9,13", "5,11,13", "1,4,7,14", "15", "6,10,11,13", "6,10", "5,6,13", "3,10", "4,13,14", "12,13", "11,15",
      "10,14", "6,12", "2,9", "1,4,8,14", "5,8", "6,9,12,13", "3,9", "3,13,14,15", "2,7,14", "2,5,15", "1,3,5,12,13", "1,2,6,12",
      "12,14,15", "1,3,6,7", "2,3,13", "6,14", "9,10,12", "4,12", "1", "2,7,9,12,15", "5,12", "5,14", "2,5,8", "4,7,14", "1,4,13",
      "6,13", "5,9,14,15", "5,7,9", "3,6", "1,11,12", "2,12", "1,10,13,14", "4,5,6", "1,4,9,13", "8,10,11", "2,7,13", "5,10,11",
      "3,10,13,14", "1,14", "2,4,13", "1,2,15", "8,10,12,13", "4,5,9", "1,12,13", "10,11,14", "7,9,13", "7,15", "3,4,14",
      "1,4,12,15", "2,14,15", "2,4,9,14,15", "6,12,13", "4,9", "8,10,11,13", "5,12,14", "10,11", "2,11", "4,6,7,10,13,14",
      "1,5,14,15", "2,13", "4,10", "3,4,9", "1,8,12", "1,5,7,9,14", "9", "2,13,14", "2,14", "9,14", "7,9,14", "2,4,12,14",
      "1,3,12,13", "2,9,13", "4,8", "4,12,14", "2,6", "2,8,13", "1,4,5,8", "1,3,7", "9,11,13", "3,7,11", "3,9,12,14,15",
      "1,2,12", "6,7,10", "5,10", "1,5", "3,5,9", "1,9,10", "6,10,11,15", "3,5", "1,4,5,8,13", "6,8", "5,6", "6,8,15", "11,13",
      "1,15", "11,12,14", "1,5,12", "1,8,12,13", "3,4", "5,7", "4,11", "5,13")
    .zipWithIndex.map(e => e._1 -> e._2.toDouble).toMap

  val genderIndex = List("M", "F", "", " ").zipWithIndex.map(e => e._1 -> e._2.toDouble).toMap

  val maritalStatusIndex =
    List("2,4,5,6", "1,3,5", "0,1,4", "0,6", "2,3,4,6", "1,3,4,5", "0,4,6", "0,3", "2,4", "2,3,4,5",
      "2,3,5", "0,2,4", "1,2,4", "0,4", "3,4,6", "4,5,6", "0,5,6", "1,4,6", "1,3,6", "2,4,6", "1,2,3", "2,3,6", "5,6", "1,3", "1,3,4",
      "3,5,6", "2,5", "1,2,6", "2,5,6", "2,3", "4,6", "3,4,5", "1,4", "1,5", "1,2", "1,2,5", "3,5", "4,5", "1,6", "2,6", "3,6", "3,4", "4",
      "", "5", "3", "6", "1", "2")
    .zipWithIndex.map(e => e._1 -> e._2.toDouble).toMap

  def fromGenderToIndex(u: AnonymizedRecord): Double = u.gender match {
    case s if s.nonEmpty => genderIndex(s.head.trim)
    case _ => genderIndex(" ")
  }

  def fromOnline(u: AnonymizedRecord): Double = u.onlineActive match {
    case s if s.nonEmpty => s.foldLeft(false)((l, r) => l | r) ? 1.0 | 2.0
    case _ => 0.0
  }

  def fromOccupational(u: AnonymizedRecord) = occupationalID(u.occupationId.flatten.toList.sorted.mkString(","))

  def fromMerchantCodes(u: AnonymizedRecord) = merchantCodes(u.merchantCategoryCode)

  def fromMarital(u: AnonymizedRecord) = maritalStatusIndex(u.maritalStatusId.flatten.toList.sorted.mkString(","))

  val featFun: Array[(AnonymizedRecord) => Double] = Array(fromMerchantCodes _, fromGenderToIndex _,
    fromMarital _, fromOccupational _)

  def fromUserToFeatures(u: AnonymizedRecord) = featFun.map(f => f(u)) //new DenseVector(featFun.map(f => f(u)))

  def features(records: RDD[AnonymizedRecord], minTxPerCustomer: Int = 5, minBudgetPerCustomer: Double = 100.0) = {

    val uniqueBusiness: RDD[((String, String), Long)] = records.map(businessID).distinct().zipWithIndex

    val custStatsMap: RDD[(Long, List[((String, String), StatCounter)])] =
      records.map(r => (r.maskedCustomerId, businessID(r)) -> StatCounter(r.amount))
      .reduceByKey(_ |+| _)
      .filter(s => (s._2.sum > minBudgetPerCustomer) && (s._2.count > minTxPerCustomer))
      .map(s => s._1._1 -> List(s._1._2 -> s._2))
      .reduceByKey(_ ++ _)

    val noNeedToCompute = custStatsMap.map(_._2.map(_._1).toSet).treeReduce((a, b) => a.intersect(b))

    val custGlobalStats: RDD[(Long, StatCounter)] = custStatsMap.map(s => s._1 -> s._2.map(_._2).reduce(_ |+| _))
    val custOnlyFeatures: RDD[(Long, Array[Double])] = records.map(r => r.maskedCustomerId -> r)
                                                       .reduceByKey((l, r) => l)
                                                       .map(u => u._1 -> fromUserToFeatures(u._2))

    val noNeedToComputeBV = custOnlyFeatures.context.broadcast(noNeedToCompute)
    val custStats: RDD[(Long, List[((String, String), StatCounter)], StatCounter, Array[Double])] =
      custStatsMap
      .join(custGlobalStats)
      .join(custOnlyFeatures)
      .mapPartitions(part => {
        val blId = noNeedToComputeBV.value

        part.map(j => {
          val (cid, ((bstats, gstats), cfeat)) = j
          (cid, bstats.flatMap(el => if (blId.contains(el._1)) None else Some(el)), gstats, cfeat)
        })
      })

    custGlobalStats.unpersist()
    custOnlyFeatures.unpersist()

    custStats.saveAsObjectFile("/data/lanzarote/customerstats")
    uniqueBusiness.saveAsObjectFile("/data/lanzarote/uniquebusinesses")
    Logger.infoLevel.error(Console.MAGENTA +
      "CustomerStats: %,d . UniqueBID: %,d".format(custStats.count(), uniqueBusiness.count()) + Console.RESET)

    val bid: Map[(String, String), Long] = uniqueBusiness.collect().toMap

    custStats.map(c => {
      val (cid, bstats, gstats, cfeat) = c
      val sb = new StringBuilder(65000)

      sb.append(bstats.map(p => bid(p._1)).mkString(",") + " ") //labels
      sb.append(fromCustFeaturesToLibsvm(cfeat, 0) + " ") //customer features
      sb.append(fromStatsToLibsvm(gstats, cfeat.length) + " ") //global stats
      sb.append(fromBStatsToLibsvm(bstats.map(el => (bid(el._1), el._2)).sortBy(_._1), cfeat.length + 6))
      sb.toString()
    }).saveAsTextFile("/data/lanzarote/featuresLibsvm")
    Logger.infoLevel.error(Console.MAGENTA + "Features created" + Console.RESET)
  }

  def fromBStatsToLibsvm(bstats: List[(Long, StatCounter)], startIndex: Int) =
    bstats.zipWithIndex
    .map(el => (el._1._2, el._2 * 6 + startIndex))
    .map(el => fromStatsToLibsvm(el._1, el._2)).mkString(" ")

  def fromStatsToLibsvm(s: StatCounter, startIndex: Int): String =
    "%d:%f %d:%f %d:%f %d:%f %d:%f %d:%d".format(
      startIndex, s.mean,
      startIndex + 1, s.variance,
      startIndex + 2, s.stderr,
      startIndex + 3, s.min,
      startIndex + 4, s.max,
      startIndex + 5, s.n)

  def fromCustFeaturesToLibsvm(feat: Array[Double], indexOffset: Int = 0): String =
    feat.toList.zipWithIndex.map(el => "%d:%d".format(el._2 + indexOffset, el._1.toInt)).mkString(" ")

  override def train(data: RDD[AnonymizedRecord]): Recommender = {
    features(data)

    val learner = new MpBoostLearner(data.context)
    learner.setNumIterations(1000)
    learner.setParallelismDegree(8)

    val classifier: BoostClassifier = learner.buildModel("/data/lanzarote/featuresLibsvm", true, false)
    DataUtils.saveModel(data.context, classifier, "/data/lanzarote/boostedModel")

    new Recommender() {
      def recommendations(customers: RDD[Long], n: Int): RDD[(Long, List[(String, String)])] = {
        //        classifier
        ???
      }
    }
  }
}