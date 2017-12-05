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

import javax.inject.Inject
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{Action, BodyParser}
import uk.gov.hmrc.leakdetection.config.ConfigLoader
import uk.gov.hmrc.leakdetection.model.PayloadDetails
import uk.gov.hmrc.leakdetection.services.ScanningService
import uk.gov.hmrc.play.bootstrap.controller.BaseController
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails

class WebhookController @Inject()(configLoader: ConfigLoader, scanningService: ScanningService)
    extends BaseController {

  val logger = Logger(classOf[WebhookController])

  def processGithubWebhook() =
    Action.async(validateAndParse) { implicit request =>
      scanningService.scanCodeBaseFromGit(request.body).map { report =>
        Ok(Json.toJson(report))
      }
    }

  val validateAndParse: BodyParser[PayloadDetails] =
    WebhookRequestValidator.parser(
      webhookSecret = configLoader.cfg.githubSecrets.webhookSecretKey
    )

}