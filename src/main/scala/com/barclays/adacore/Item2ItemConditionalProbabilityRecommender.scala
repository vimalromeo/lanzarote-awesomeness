package com.barclays.adacore

import org.apache.spark.SparkContext
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import scalaz.Scalaz._

case class Item2ItemConditionalProbabilityRecommender(sc: SparkContext, minNumTransactions: Int, maxDepth: Int) extends RecommenderTrainer {
  def train(data: RDD[AnonymizedRecord]): Recommender = {

    val filteredCustomersAndBusinesses: RDD[(Long, (String, String))] =
      data.map(record => (record.maskedCustomerId, record.businessKey) -> 1).reduceByKey(_ + _)
      .filter(_._2 >= minNumTransactions).keys.cache()

    val customerIdToBusinessSet: Broadcast[Map[Long, Set[(String, String)]]] =
      sc.broadcast(filteredCustomersAndBusinesses.mapValues(Set(_)).reduceByKey(_ ++ _).collect().toMap)

    val businessKeyToCustomerSet: RDD[((String, String), Set[Long])] =
      filteredCustomersAndBusinesses.map(_.swap).mapValues(Set(_)).reduceByKey(_ ++ _).cache()

    val item2itemMatrix: Broadcast[Map[(String, String), List[((String, String), Double)]]] = sc.broadcast(
      businessKeyToCustomerSet.cartesian(businessKeyToCustomerSet).flatMap {
        case ((businessKey1, userSet1), (businessKey2, userSet2)) =>
          val conditionalProb = userSet1.intersect(userSet2).size.toDouble / userSet2.size

          (businessKey1 != businessKey2 && conditionalProb > 0)
          .option(businessKey1 -> List(businessKey2 -> conditionalProb))
      }
      .reduceByKey(_ ++ _)
      .collect().toMap)

    filteredCustomersAndBusinesses.unpersist()
    businessKeyToCustomerSet.unpersist()

    new Recommender {
      // returns customerId -> List[(merchantName, merchantTown)]
      def recommendations(customers: RDD[Long], n: Int): RDD[(Long, List[(String, String)])] = {
        (for {
          customerId <- customers
          customerBusinessKeys = customerIdToBusinessSet.value(customerId)
          customerBusinessKey <- customerBusinessKeys
          (similarBusiness, conditionalProb) <- item2itemMatrix.value(customerBusinessKey)
          if !customerBusinessKeys.contains(similarBusiness)
        } yield (customerId, similarBusiness) -> conditionalProb
          )
        .reduceByKey(_ + _)
        .groupBy(_._1._1).mapValues(_.toList.sortBy(_._2).reverse.take(n).map(_._1._2))
      }
    }
  }
}
