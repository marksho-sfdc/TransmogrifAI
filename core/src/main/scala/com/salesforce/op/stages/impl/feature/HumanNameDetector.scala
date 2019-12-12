/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.salesforce.op.stages.impl.feature

import com.salesforce.op._
import com.salesforce.op.features.types._
import com.salesforce.op.stages.base.unary.{UnaryEstimator, UnaryModel}
import com.salesforce.op.stages.impl.MetadataLike
import com.salesforce.op.utils.stages.NameDetectUtils.GenderDictionary
import com.salesforce.op.utils.stages._
import com.twitter.algebird.Operators._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.sql._
import org.apache.spark.sql.types.{Metadata, MetadataBuilder}

import scala.reflect.runtime.universe.TypeTag

/**
 * Unary estimator for identifying whether a single Text column is a name or not. If the column does appear to be a
 * name, a custom map will be returned that contains the guessed gender for each entry. If the column does not appear
 * to be a name, then the output will be an empty map.
 * @param uid           uid for instance
 * @param operationName unique name of the operation this stage performs
 * @param tti           type tag for input
 * @param ttiv          type tag for input value
 * @tparam T            the FeatureType (subtype of Text) to operate over
 */
class HumanNameDetector[T <: Text]
(
  uid: String = UID[HumanNameDetector[T]],
  operationName: String = "human name detector"
)
(
  implicit tti: TypeTag[T],
  override val ttiv: TypeTag[T#Value]
) extends UnaryEstimator[T, NameStats](
  uid = uid,
  operationName = operationName
) with NameDetectFun {

  def fitFn(dataset: Dataset[T#Value]): HumanNameDetectorModel[T] = {
    implicit val (nameDetectStatsEnc, nameDetectStatsMonoid) = makeImplicits
    val mapFun: T#Value => NameDetectStats = makeMapFunction(dataset.sparkSession)
    val aggResults: NameDetectStats = dataset.map(mapFun).reduce(_ + _)
    val treatAsName = computeTreatAsName(aggResults)

    val orderedGenderDetectStrategies = if (treatAsName) {
      val ordered: Seq[(String, GenderStats)] = aggResults.genderResultsByStrategy.toSeq.sortBy(_._2.numOther)
      ordered map { case (strategy, _) => GenderDetectStrategy.fromString(strategy) }
    } else Seq.empty[GenderDetectStrategy]

    val newMetadata = HumanNameDetectorMetadata(
      treatAsName, aggResults.dictCheckResult.value, aggResults.genderResultsByStrategy
    ).toMetadata()
    val metaDataBuilder = new MetadataBuilder().withMetadata(getMetadata()).withMetadata(newMetadata)
    setMetadata(metaDataBuilder.build())

    new HumanNameDetectorModel[T](uid, treatAsName, orderedGenderDetectStrategies)
  }
}

case class HumanNameDetectorMetadata
(
  treatAsName: Boolean,
  predictedNameProb: Double,
  genderResultsByStrategy: Map[String, GenderStats]
) extends MetadataLike {
  import HumanNameDetectorMetadata._

  override def toMetadata(): Metadata = {
    val metaDataBuilder = new MetadataBuilder()
    metaDataBuilder.putBoolean(TreatAsNameKey, treatAsName)
    metaDataBuilder.putDouble(PredictedNameProbKey, predictedNameProb)
    val genderResultsMetaDataBuilder = new MetadataBuilder()
    genderResultsByStrategy foreach { case (strategyString, stats) =>
      genderResultsMetaDataBuilder.putDoubleArray(strategyString, Array(stats.numMale, stats.numFemale, stats.numOther))
    }
    metaDataBuilder.putMetadata(GenderResultsByStrategyKey, genderResultsMetaDataBuilder.build())
    metaDataBuilder.build()
  }

  override def toMetadata(skipUnsupported: Boolean): Metadata = toMetadata()
}
case object HumanNameDetectorMetadata {
  val TreatAsNameKey = "treatAsName"
  val PredictedNameProbKey = "predictedNameProb"
  val GenderResultsByStrategyKey = "genderResultsByStrategy"

  def fromMetadata(metadata: Metadata): HumanNameDetectorMetadata = {
    val genderResultsMetadata = metadata.getMetadata(GenderResultsByStrategyKey)
    val genderResultsByStrategy: Map[String, GenderStats] = {
      NameDetectUtils.GenderDetectStrategies map { strategy: GenderDetectStrategy =>
        val strategyString = strategy.toString
        val resultsArray = genderResultsMetadata.getDoubleArray(strategyString)
        require(resultsArray.length == 3, "There must be exactly three values for each gender detection strategy.")
        strategyString -> GenderStats(resultsArray(0).toInt, resultsArray(1).toInt, resultsArray(2).toInt)
      } toMap
    }
    HumanNameDetectorMetadata(
      metadata.getBoolean(TreatAsNameKey),
      metadata.getDouble(PredictedNameProbKey),
      genderResultsByStrategy
    )
  }
}


class HumanNameDetectorModel[T <: Text]
(
  override val uid: String,
  val treatAsName: Boolean,
  val orderedGenderDetectStrategies: Seq[GenderDetectStrategy] = Seq.empty[GenderDetectStrategy]
)(implicit tti: TypeTag[T])
  extends UnaryModel[T, NameStats]("human name detector", uid) with NameDetectFun {

  var broadcastGenderDict: Option[Broadcast[GenderDictionary]] = None

  override def transform(dataset: Dataset[_]): DataFrame = {
    val spark: SparkSession = dataset.sparkSession
    this.broadcastGenderDict = Some(spark.sparkContext.broadcast(NameDetectUtils.DefaultGenderDictionary))
    super.transform(dataset)
  }

  import NameStats.BooleanStrings._
  import NameStats.GenderStrings.GenderNA
  import NameStats.Keys._
  def transformFn: T => NameStats = (input: T) => {
    val tokens = preProcess(input.value)
    if (treatAsName) {
      require(orderedGenderDetectStrategies.nonEmpty, "There must be a gender extraction strategy if treating as name.")
      require(this.broadcastGenderDict.nonEmpty, "Gender dictionary broadcast variable was not initialized correctly.")
      val genders: Seq[String] = orderedGenderDetectStrategies map {
        identifyGender(input.value.getOrElse(""), tokens, _, this.broadcastGenderDict.get)
      }
      val gender = genders.find(_ != GenderNA).getOrElse(GenderNA)
      NameStats(Map(
        IsNameIndicator -> True,
        OriginalName -> input.value.getOrElse(""),
        Gender -> gender
      ))
    }
    else NameStats(Map.empty[String, String])
  }
}
