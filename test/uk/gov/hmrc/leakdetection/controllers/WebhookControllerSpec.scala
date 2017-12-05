/*
 * Copyright 2017 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.leakdetection.controllers

import ModelFactory._
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.StreamConverters
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import concurrent.duration._
import java.io.{BufferedOutputStream, ByteArrayInputStream, ByteArrayOutputStream}
import java.util.UUID
import java.util.zip.{ZipEntry, ZipOutputStream}
import org.apache.commons.codec.digest.HmacUtils
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}
import org.scalatestplus.play.OneAppPerSuite
import play.api._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.mvc.{Action, Results}
import play.api.routing.sird._
import play.api.test.Helpers.{CONTENT_DISPOSITION, CONTENT_TYPE}
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.leakdetection.TestServer
import uk.gov.hmrc.leakdetection.model.Report

class WebhookControllerSpec
    extends FeatureSpec
    with GivenWhenThen
    with Matchers
    with OneAppPerSuite
    with Fixtures {

  feature("Verifying Github commits") {

    scenario("happy path") {

      withFakeGithub {
        Given("Github makes a request with all required fields incl. a link to download a zip")
        And("repository is private")
        val githubRequestPayload: String =
          asJson(aPayloadDetails.copy(archiveUrl = archiveUrl, isPrivate = true))

        And("the request is signed using a secret known to us")
        val signedRequest =
          FakeRequest("POST", "/leak-detection/validate")
            .withBody(githubRequestPayload)
            .withHeaders(
              CONTENT_TYPE      -> "application/json",
              "X-Hub-Signature" -> ("sha1=" + HmacUtils.hmacSha1Hex(secret, githubRequestPayload))
            )

        And("Github, when called will return a zip with source code files")
        filesInTheArchive = List(
          TestZippedFile(content = "package foo \n var x = null"),
          TestZippedFile(content = "Option(1).getOrElse(throw SadnessException)")
        )

        When("Leak Detection service receives a request")
        val res = Helpers.route(app, signedRequest).get

        Then("Processing should be successful")
        Helpers.status(res) shouldBe 200

        And("Report should include info about all found problems")
        val report = Json.parse(Helpers.contentAsString(res)).as[Report]
        report.inspectionResults.size shouldBe 2

      }
    }
  }

}

trait Fixtures { self: OneAppPerSuite =>

  implicit val timeout                = Timeout(5.seconds)
  implicit val system: ActorSystem    = ActorSystem()
  implicit val mat: ActorMaterializer = ActorMaterializer()

  val secret = aString()

  implicit override lazy val app: Application =
    new GuiceApplicationBuilder()
      .configure(
        Configuration(
          ConfigFactory.parseString(
            s"""
              allRules {
                publicRules = []
                privateRules = [
                  {
                   regex = "^.*(null).*$$"
                   tag = "uses nulls!"
                  },
                  {
                   regex = "^.*(throw).*$$"
                   tag = "throws exceptions!"
                  }
                ]
              }

              githubSecrets {
                webhookSecretKey = $secret
                personalAccessToken = pat
              }
            """
          ))
      )
      .build

  def withFakeGithub(block: => Any): Any =
    try {
      block
    } finally {
      server.stop
    }

  val server =
    TestServer {
      case GET(p"/") =>
        Action {
          Results.Ok
            .chunked(StreamConverters.fromInputStream(createZip))
            .withHeaders(
              CONTENT_TYPE        -> "application/zip",
              CONTENT_DISPOSITION -> s"attachment; filename = test.zip"
            )
        }
    }

  val archiveUrl = s"http://localhost:${server.httpPort.get}/"

  var filesInTheArchive: List[TestZippedFile] = _

  def createZip(): ByteArrayInputStream = {
    val baos = new ByteArrayOutputStream()
    val zos  = new ZipOutputStream(new BufferedOutputStream(baos))

    try {
      filesInTheArchive.foreach { file =>
        zos.putNextEntry(new ZipEntry(file.path))
        zos.write(file.content.getBytes("UTF-8"))
        zos.closeEntry()
      }
    } finally {
      zos.close()
    }

    new ByteArrayInputStream(baos.toByteArray)
  }

}

case class TestZippedFile(
  content: String,
  path: String = s"repo/${UUID.randomUUID().toString}"
)
