#!/usr/bin/env bash

JENKINS_URL=$1
AGENT_NAME=$2
SECRET=$3

mkdir /opt/jenkins

(
  cd /opt/jenkins
  apt-get update
  apt-get install -y default-jdk git

  MAVEN_VERSION=3.9.1
  curl -O "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
  tar zxvf apache-maven-${MAVEN_VERSION}-bin.tar.gz -C /opt/
  ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/bin/mvn

  mvn --version

  curl -O "$JENKINS_URL/jnlpJars/agent.jar"

  java -jar agent.jar -jnlpUrl "$JENKINS_URL/computer/$AGENT_NAME/jenkins-agent.jnlp" -secret "$SECRET" -workDir /opt/jenkins/workDir
) |& tee /opt/jenkins/init-script.log
