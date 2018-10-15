#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
sed -i -e 's/#//' -e 's/default_ccache_name/# default_ccache_name/' /etc/krb5.conf
export HADOOP_OPTS="-Djava.net.preferIPv4Stack=true -Dsun.security.krb5.debug=true"
export HADOOP_JAAS_DEBUG=true
export HADOOP_ROOT_LOGGER=DEBUG,console
cp ${TMP_KRB_LOC} /etc/krb5.conf
cp ${TMP_CORE_LOC} /opt/spark/hconf/core-site.xml
cp ${TMP_HDFS_LOC} /opt/spark/hconf/hdfs-site.xml
mkdir -p /etc/krb5.conf.d
/opt/spark/bin/spark-submit \
      --deploy-mode cluster \
      --class ${CLASS_NAME} \
      --master k8s://${MASTER_URL} \
      --conf spark.kubernetes.namespace=${NAMESPACE} \
      --conf spark.executor.instances=1 \
      --conf spark.app.name=spark-hdfs \
      --conf spark.driver.extraClassPath=/opt/spark/hconf/core-site.xml:/opt/spark/hconf/hdfs-site.xml:/opt/spark/hconf/yarn-site.xml:/etc/krb5.conf \
      --conf spark.kubernetes.container.image=${BASE_SPARK_IMAGE} \
      --conf spark.kubernetes.kerberos.krb5.path=/etc/krb5.conf \
      --conf spark.kerberos.keytab=/var/keytabs/hdfs.keytab \
      --conf spark.kerberos.principal=hdfs/nn.${NAMESPACE}.svc.cluster.local@CLUSTER.LOCAL \
      --conf spark.kubernetes.driver.label.spark-app-locator=${APP_LOCATOR_LABEL} \
      ${SUBMIT_RESOURCE} \
      hdfs://nn.${NAMESPACE}.svc.cluster.local:9000/user/ifilonenko/people.txt
