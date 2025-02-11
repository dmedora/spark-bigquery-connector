/*
 * Copyright 2018 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.spark.bigquery

import org.apache.spark.internal.Logging

import java.util.{Optional, Properties}

/**
 * Static helpers for working with BigQuery, relevant only to the Scala code
 */
object BigQueryUtilScala extends Logging{

  // validating that the connector's scala version and the runtime's scala
  // version are the same
  def validateScalaVersionCompatibility(): Unit = {
    val runtimeScalaVersion = trimVersion(scala.util.Properties.versionNumberString)
    val buildProperties = new Properties
    buildProperties.load(getClass.getResourceAsStream("/spark-bigquery-connector.properties"))
    val connectorScalaVersion = buildProperties.getProperty("scala.binary.version")
    if (!runtimeScalaVersion.equals(connectorScalaVersion)) {
      throw new IllegalStateException(
        s"""
           |This connector was made for Scala $connectorScalaVersion,
           |it was not meant to run on Scala $runtimeScalaVersion"""
          .stripMargin.replace('\n', ' '))
    }
  }

  private def trimVersion(version: String) =
    version.substring(0, version.lastIndexOf('.'))

  def toOption[T](javaOptional: Optional[T]): Option[T] =
    if (javaOptional.isPresent) Some(javaOptional.get) else None
}
