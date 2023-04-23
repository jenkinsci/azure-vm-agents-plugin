# Install Maven

MAVEN_VERSION=3.9.1

sudo curl -O "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
sudo tar zxvf apache-maven-${MAVEN_VERSION}-bin.tar.gz -C /opt/
sudo ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/bin/mvn

mvn --version
