/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.deploy.kubernetes.integrationtest.sslutil

import java.io.{File, FileOutputStream}
import java.math.BigInteger
import java.nio.file.Files
import java.security.{KeyPairGenerator, KeyStore, SecureRandom}
import java.util.{Calendar, Random}
import javax.security.auth.x500.X500Principal

import org.bouncycastle.asn1.x509.{Extension, GeneralName, GeneralNames}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

import org.apache.spark.util.Utils

private[spark] object SSLUtils {

  def generateKeyStoreTrustStorePair(
      ipAddress: String,
      keyStorePassword: String,
      keyPassword: String,
      trustStorePassword: String): (File, File) = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(512)
    val keyPair = keyPairGenerator.generateKeyPair()
    val selfPrincipal = new X500Principal(s"cn=$ipAddress")
    val currentDate = Calendar.getInstance
    val validForOneHundredYears = Calendar.getInstance
    validForOneHundredYears.add(Calendar.YEAR, 100)
    val certificateBuilder = new JcaX509v3CertificateBuilder(
      selfPrincipal,
      new BigInteger(4096, new Random()),
      currentDate.getTime,
      validForOneHundredYears.getTime,
      selfPrincipal,
      keyPair.getPublic)
    certificateBuilder.addExtension(Extension.subjectAlternativeName, false,
      new GeneralNames(new GeneralName(GeneralName.iPAddress, ipAddress)))
    val signer = new JcaContentSignerBuilder("SHA1WithRSA")
      .setSecureRandom(new SecureRandom())
      .build(keyPair.getPrivate)
    val bcCertificate = certificateBuilder.build(signer)
    val jcaCertificate = new JcaX509CertificateConverter().getCertificate(bcCertificate)
    val keyStore = KeyStore.getInstance("JKS")
    keyStore.load(null, null)
    keyStore.setKeyEntry("key", keyPair.getPrivate,
      keyPassword.toCharArray, Array(jcaCertificate))
    val tempDir = Files.createTempDirectory("temp-ssl-stores").toFile()
    tempDir.deleteOnExit()
    val keyStoreFile = new File(tempDir, "keyStore.jks")
    Utils.tryWithResource(new FileOutputStream(keyStoreFile)) {
      keyStore.store(_, keyStorePassword.toCharArray)
    }
    val trustStore = KeyStore.getInstance("JKS")
    trustStore.load(null, null)
    trustStore.setCertificateEntry("key", jcaCertificate)
    val trustStoreFile = new File(tempDir, "trustStore.jks")
    Utils.tryWithResource(new FileOutputStream(trustStoreFile)) {
      trustStore.store(_, trustStorePassword.toCharArray)
    }
    (keyStoreFile, trustStoreFile)
  }

}
