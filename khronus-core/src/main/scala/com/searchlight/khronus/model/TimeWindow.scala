/*
 * =========================================================================================
 * Copyright © 2015 the khronus project <https://github.com/hotels-tech/khronus>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package com.searchlight.khronus.model

import com.searchlight.khronus.model.bucket.{ CounterBucket, GaugeBucket, HistogramBucket }
import com.searchlight.khronus.model.summary.{ CounterSummary, GaugeSummary, HistogramSummary }
import com.searchlight.khronus.dao._
import com.searchlight.khronus.util.log.Logging
import com.searchlight.khronus.util.{ ConcurrencySupport, Measurable }

import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

trait Window {
  def process(implicit metric: Metric, tick: Tick): Future[Unit]

  def duration: Duration
}

abstract class TimeWindow[T <: Bucket, S <: Summary] extends Window with BucketStoreSupport[T] with SummaryStoreSupport[S] with MetaSupport with Logging with Measurable with BucketCacheSupport[T] {

  import com.searchlight.khronus.model.TimeWindow._

  private val durationText = duration.toString

  def process(implicit metric: Metric, tick: Tick): Future[Unit] = measureAndCheckForTimeOutliers("processWindow", metric, duration) {
    implicit val context = Context(metric, durationText)

    //get the last bucket processed for this window
    withLastProcessedBucket { lastProcessedBucket ⇒

      log.debug(s"$context - Processing time window for ${Tick(tick.bucketNumber ~ duration)}")

      //we align both bucket numbers (lastProcessedBucketNumber and Tick) to the previousWindowDuration
      val fromBucketNumber = lastProcessedBucket.endTimestamp().toBucketNumberOf(previousWindowDuration)
      //since the slices over bucketStore and bucketCache are exclusives at the end, we use the Tick's following bucket number as the end of the slices
      val toBucketNumber = tick.bucketNumber.following ~ duration ~ previousWindowDuration

      //retrieve the buckets from previous window
      withPreviousWindowBuckets(fromBucketNumber, toBucketNumber) { previousWindowBuckets ⇒
        //group in buckets of my window duration
        val myBuckets = aggregateBuckets(grouped(previousWindowBuckets))
        //calculate the summaries
        val mySummaries = myBuckets map (bucket ⇒ summary(bucket))
        //store temporal buckets for next window if needed
        //store the summaries
        val stores = storeBucketsAndSummaries(myBuckets, mySummaries, fromBucketNumber, toBucketNumber)
        stores.map { _ ⇒ bucketCache.markProcessedTick(tick, metric) }
      }
    }
  }

  private def storeBucketsAndSummaries(buckets: Seq[T], summaries: Seq[S], from: BucketNumber, to: BucketNumber)(implicit metric: Metric, tick: Tick, context: Context) = {
    storeBuckets(buckets, from, to) flatMap { _ ⇒
      summaryStore.store(metric, duration, summaries)
    }
  }

  private def withLastProcessedBucket[B](block: BucketNumber ⇒ Future[B])(implicit metric: Metric, tick: Tick, context: Context) = {
    lastProcessedBucket(metric) flatMap block
  }

  private def withPreviousWindowBuckets[B](from: BucketNumber, to: BucketNumber)(block: BucketSlice[T] ⇒ Future[B])(implicit metric: Metric, tick: Tick, context: Context) = {
    retrievePreviousBuckets(metric, tick, from, to) flatMap block
  }

  protected def summary(bucket: T): S

  private def storeBuckets(buckets: Seq[T], from: BucketNumber, to: BucketNumber)(implicit tick: Tick, metric: Metric, context: Context) = {
    if (shouldStoreBuckets) measureFutureTime("storeTemporalBuckets", metric, duration) {
      val storeFuture = bucketStore.store(metric, duration, buckets)
      storeFuture.onFailure { case reason: Throwable ⇒ log.error(s"$context - Fail to store temporal buckets", reason) }
      storeFuture.map { _ ⇒
        bucketCache.multiSet(metric, from ~ duration, to ~ duration, buckets)
      }
    }
    else {
      Future.successful[Unit](log.debug(s"$context - Last window. No need to store buckets"))
    }
  }

  private def grouped(buckets: BucketSlice[T])(implicit metric: Metric, context: Context): Map[BucketNumber, Seq[T]] = {
    val groupedBuckets = buckets.results.groupBy(bucketResult ⇒ bucketResult.timestamp.toBucketNumberOf(duration)).mapValues(
      seq ⇒ seq.view.map(bucketResult ⇒ bucketResult.lazyBucket()))
    if (groupedBuckets.nonEmpty) {
      log.debug(s"$context - Grouped ${groupedBuckets.size} buckets ${groupedBuckets.keys}")
    }
    groupedBuckets
  }

  protected def aggregateBuckets(bucketsMap: Map[BucketNumber, Seq[T]]): Seq[T] = {
    bucketsMap.collect { case (bucketNumber, buckets) ⇒ aggregate(bucketNumber, buckets) }.toSeq
  }

  protected def aggregate(bucketNumber: BucketNumber, buckets: Seq[T]): T

  protected def previousWindowDuration: Duration

  protected def shouldStoreBuckets: Boolean

  private def retrievePreviousBuckets(metric: Metric, tick: Tick, fromBucketNumber: BucketNumber, toBucketNumber: BucketNumber)(implicit context: Context) = {
    val start = System.currentTimeMillis()

    log.debug(s"${p(metric, duration)} - Slice [${date(fromBucketNumber.startTimestamp())}, ${date(toBucketNumber.startTimestamp())})")

    bucketCache.multiGet(metric, duration, fromBucketNumber, toBucketNumber).map { buckets ⇒
      if (buckets.results.isEmpty) notifyEmptySlice(metric, duration)
      Future.successful(buckets)
    }.getOrElse {
      val futureSlice = bucketStore.slice(metric, fromBucketNumber.startTimestamp(), toBucketNumber.startTimestamp(), previousWindowDuration)
      futureSlice.map { bucketSlice ⇒
        if (bucketSlice.results.isEmpty) {
          recordHistogram(formatLabel("emptySliceTime", metric, duration), System.currentTimeMillis() - start)
          notifyEmptySlice(metric, duration)
        }
        log.debug(s"$context - Found ${bucketSlice.results.size} buckets of $previousWindowDuration")
        bucketSlice
      }
    }
  }

  private def lastProcessedBucket(metric: Metric): Future[BucketNumber] = {
    metaStore.getLastProcessedTimestamp(metric) map { lastTS ⇒ Timestamp(lastTS.ms - duration.toMillis).alignedTo(duration).toBucketNumberOf(duration) } andThen {
      case Success(bucket) ⇒
        log.trace(s"${p(metric, duration)} - Last processed bucket: $bucket")
      case Failure(reason) ⇒ log.error(s"Fail to recover lastProcessedBucket for $metric", reason)
    }
  }

  private def notifyEmptySlice(metric: Metric, duration: Duration) = {
    metaStore.notifyEmptySlice(metric, duration)
  }

}

object TimeWindow extends ConcurrencySupport {
  implicit val executionContext: ExecutionContext = executionContext("time-window-worker")
}

case class CounterTimeWindow(duration: Duration, previousWindowDuration: Duration, shouldStoreBuckets: Boolean = true)
    extends TimeWindow[CounterBucket, CounterSummary] with CounterBucketStoreSupport with CounterSummaryStoreSupport {

  override def summary(bucket: CounterBucket) = bucket.summary

  override def aggregate(bucketNumber: BucketNumber, buckets: Seq[CounterBucket]): CounterBucket = new CounterBucket(bucketNumber, buckets)

  override val bucketCache: BucketCache[CounterBucket] = InMemoryCounterBucketCache
}

case class HistogramTimeWindow(duration: Duration, previousWindowDuration: Duration, shouldStoreBuckets: Boolean = true)
    extends TimeWindow[HistogramBucket, HistogramSummary] with HistogramBucketSupport with HistogramSummarySupport {

  override def summary(bucket: HistogramBucket) = bucket.summary

  override def aggregate(bucketNumber: BucketNumber, buckets: Seq[HistogramBucket]): HistogramBucket = HistogramBucket.aggregate(bucketNumber, buckets)

  override val bucketCache: BucketCache[HistogramBucket] = InMemoryHistogramBucketCache
}

case class GaugeTimeWindow(duration: Duration, previousWindowDuration: Duration, shouldStoreBuckets: Boolean = true)
    extends TimeWindow[GaugeBucket, GaugeSummary] with GaugeBucketStoreSupport with GaugeSummaryStoreSupport {
  override def summary(bucket: GaugeBucket) = bucket.summary

  override def aggregate(bucketNumber: BucketNumber, buckets: Seq[GaugeBucket]): GaugeBucket = GaugeBucket.aggregate(bucketNumber, buckets)

  override val bucketCache: BucketCache[GaugeBucket] = InMemoryGaugeBucketCache
}

case class Context(metric: Metric, durationStr: String) extends Logging {
  override val toString = p(metric, durationStr)
}