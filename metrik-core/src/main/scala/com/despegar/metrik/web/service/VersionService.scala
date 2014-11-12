/*
 * =========================================================================================
 * Copyright © 2014 the metrik project <https://github.com/hotels-tech/metrik>
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

package com.despegar.metrik.web.service

import akka.actor.Props
import com.despegar.metrik.model.MyJsonProtocol._
import com.despegar.metrik.model.Version
import spray.http.MediaTypes._
import spray.httpx.SprayJsonSupport._
import spray.routing._

class VersionActor extends HttpServiceActor with VersionEndpoint {
  def receive = runRoute(versionRoute)
}

object VersionActor {
  def props = Props[VersionActor]
}

trait VersionEndpoint extends HttpService {
  val versionRoute: Route =
    get {
      respondWithMediaType(`application/json`) {
        // XML is marshalled to `text/xml` by default, so we simply override here
        complete {
          Version("Metrik", "0.0.1-ALPHA")
        }
      }
    }
}
