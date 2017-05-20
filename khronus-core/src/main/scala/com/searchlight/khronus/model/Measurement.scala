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

import com.fasterxml.jackson.databind.annotation.JsonDeserialize

case class MetricBatch(metrics: List[MetricMeasurement])

case class MetricMeasurement(name: String, mtype: String, measurements: List[Measurement], tags: Map[String, String] = Map()) {

  import MetricMeasurement._

  override def toString = s"Metric($name,$mtype)"

  def asMetric = Metric(name, mtype, Option(tags).getOrElse(EMPTY_TAGS))

}

object MetricMeasurement {
  val EMPTY_TAGS: Map[String, String] = Map()
}

case class Measurement(@JsonDeserialize(contentAs = classOf[java.lang.Long]) ts: Option[Long],
  @JsonDeserialize(contentAs = classOf[java.lang.Long]) values: Seq[Long])

