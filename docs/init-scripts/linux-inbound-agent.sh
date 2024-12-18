#!/usr/bin/env bash

JENKINS_URL=$1
AGENT_NAME=$2
SECRET=$3

# Update if your user is called something different
export USER=jenkins

mkdir -p /opt/jenkins
mkdir -p /home/$USER/inbound-agent
chown $USER:$USER /home/$USER/inbound-agent

(
  cd /opt/jenkins || exit
  apt-get update
  apt-get install -y default-jdk git

  MAVEN_VERSION=3.9.9
  curl -O "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
  tar zxvf apache-maven-${MAVEN_VERSION}-bin.tar.gz -C /opt/
  ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/bin/mvn

  mvn --version

  cd /home/$USER/inbound-agent || exit
  curl -O "$JENKINS_URL/jnlpJars/agent.jar"
  echo "${SECRET}" > agent-secret

  curl -O https://raw.githubusercontent.com/jenkinsci/azure-vm-agents-plugin/HEAD/docs/init-scripts/systemd-unit.service
  export AGENT_URL="$JENKINS_URL/computer/$AGENT_NAME/jenkins-agent.jnlp"
  envsubst < systemd-unit.service > /etc/systemd/system/jenkins-agent.service
  rm -f systemd-unit.service

  sudo systemctl daemon-reload
  sudo systemctl enable jenkins-agent
  sudo systemctl start jenkins-agent || sudo systemctl status jenkins-agent
) |& tee /home/$USER/inbound-agent/init-script.log
