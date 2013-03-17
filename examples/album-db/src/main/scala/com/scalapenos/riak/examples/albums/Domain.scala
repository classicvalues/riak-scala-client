/*
 * Copyright (C) 2012-2013 Age Mooij (http://scalapenos.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.scalapenos.riak.examples.albums

import spray.json.DefaultJsonProtocol._


case class Track (number: Int, title: String)
object Track {
  implicit val jsonFormat = jsonFormat2(Track.apply)
}

case class Album (
  title: String,
  artist: String,
  releasedIn: Int,
  tracks: List[Track]
)

object Album {
  implicit val jsonFormat = jsonFormat4(Album.apply)
}
