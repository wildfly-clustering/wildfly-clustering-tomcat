[![Version](https://img.shields.io/maven-central/v/org.wildfly.clustering/wildfly-clustering-tomcat?style=for-the-badge&logo=redhat&logoColor=ee0000&label=latest)](https://search.maven.org/artifact/org.wildfly.clustering/wildfly-clustering-tomcat)
[![License](https://img.shields.io/github/license/wildfly-clustering/wildfly-clustering-tomcat?style=for-the-badge&color=darkgreen&logo=apache&logoColor=d22128)](https://www.apache.org/licenses/LICENSE-2.0)
[![Project Chat](https://img.shields.io/badge/zulip-chat-lightblue.svg?style=for-the-badge&logo=zulip&logoColor=ffffff)](https://wildfly.zulipchat.com/#narrow/stream/wildfly-clustering)

# wildfly-clustering-tomcat

A high-availability session manager for Tomcat based on WildFly's distributed session management and Infinispan server.


## Building

1.	Clone this repository and build for a specific Tomcat version using Java 11+ and a standard maven build.

		$ git clone git@github.com:wildfly-clustering/wildfly-clustering-tomcat.git
		$ cd wildfly-clustering-tomcat
		$ mvn clean install -Dtomcat.version=10.1 -DskipTests=true

## Installation

1.	Copy the releveant maven artifact to Tomcat's lib directory:

		$ mvn --projects 10.1/hotrod dependency:copy -DoutputDirectory=$CATALINA_HOME/lib

1.	Copy the relevant runtime dependencies to Tomcat's lib directory:

		$ mvn --projects 10.1/hotrod dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=$CATALINA_HOME/lib

## Configuration

Define the distributed Manager implementation either within the global `$CATALINA_HOME/conf/context.xml`, or within the `/WEB-INF/context.xml` of a web application:

	<Manager className="org.wildfly.clustering.tomcat.hotrod.HotRodManager"/>

### Configuration Properties

#### Implementation specific properties

|Property|Description|
|:---|:---|
|uri|Defines a HotRod URI, which includes a list of infinispan server instances and any authentication details. For details, see: https://infinispan.org/blog/2020/05/26/hotrod-uri/|
|template|Defines the server-side configuration template from which a deployment cache is created on the server. Default is `org.infinispan.DIST_SYNC`.|
|granularity|Defines how a session is mapped to entries in the cache. "SESSION" will store all attributes of a session in a single cache entry.  "ATTRIBUTE" will store each session attribute in a separate cache entry.  Default is "SESSION".|
|maxActiveSessions|Defines the maximum number of sessions to retain in the near cache. Default is limitless. A value of 0 will disable the near cache.|
|marshaller|Specifies the marshaller used to serialize and deserialize session attributes.  Supported marshallers include: JAVA, JBOSS, PROTOSTREAM.  Default marshaller is "JBOSS".|

#### HotRod properties

These are configured without their "infinispan.client.hotrod." prefix:

https://github.com/infinispan/infinispan/blob/13.0.x/client/hotrod-client/src/main/java/org/infinispan/client/hotrod/impl/ConfigurationProperties.java

#### Common Manager properties

https://tomcat.apache.org/tomcat-10.1-doc/config/cluster-manager.html#Common_Attributes

#### Example

	<Manager className="org.wildfly.clustering.tomcat.hotrod.HotRodManager"
	         uri="hotrod://127.0.0.1:11222"
	         template="transactional"
	         granularity="ATTRIBUTE"
	         marshaller="PROTOSTREAM"
	         maxActiveSessions="100"
	         tcp_keep_alive="true"/>
