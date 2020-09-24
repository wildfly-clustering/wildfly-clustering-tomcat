# wildfly-clustering-tomcat

A distributed session manager for Tomcat based on WildFly's distributed session management.


## Building

1.	Clone this repository and build using Java 8 and a standard maven build.

		$ git clone git@github.com:wildfly-clustering/wildfly-clustering-tomcat.git
		$ cd wildfly-clustering-tomcat
		$ mvn clean install

## Installation

1.	Enter directory of target Tomcat version and session manager implementation:

		$ cd 9.0/hotrod

1.	Copy maven artifact to Tomcat's lib directory:

		$ mvn dependency:copy -DoutputDirectory=$CATALINA_HOME/lib

1.	Copy runtime dependencies to Tomcat's lib directory:

		$ mvn dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=$CATALINA_HOME/lib

## Configuration

Define the distributed Manager implementation either within the global `$CATALINA_HOME/conf/context.xml`, or within the `/WEB-INF/context.xml` of a web application:

	<Manager className="org.wildfly.clustering.tomcat.hotrod.HotRodManager"/>

### Configuration Properties

#### Implementation specific properties

|Property|Description|
|:---|:---|
|template|Defines the server-side configuration template from which a deployment cache is created on the server.  If undefined, the configuration of the server's default cache will be used.|
|granularity|Defines how a session is mapped to entries in the cache. "SESSION" will store all attributes of a session in a single cache entry.  "ATTRIBUTE" will store each session attribute in a separate cache entry.  Default is "SESSION".|
|maxActiveSessions|Defines the maximum number of sessions to retain in the near cache. Default is limitless. A value of 0 will disable the near cache.|
|marshaller|Specifies the marshaller used to serialize and deserialize session attributes.  Supported marshallers include: JAVA, JBOSS, PROTOSTREAM.  Default marshaller is "JBOSS".|

#### HotRod properties

These are configured without their "infinispan.client.hotrod." prefix:

https://github.com/infinispan/infinispan/blob/9.4.x/client/hotrod-client/src/main/java/org/infinispan/client/hotrod/impl/ConfigurationProperties.java

#### Common Manager properties

https://tomcat.apache.org/tomcat-9.0-doc/config/cluster-manager.html#Common_Attributes

#### Example

	<Manager className="org.wildfly.clustering.tomcat.hotrod.HotRodManager"
	         template="transactional"
	         granularity="ATTRIBUTE"
	         marshaller="PROTOSTREAM"
	         maxActiveSessions="100"
	         server_list="127.0.0.1:11222;127.0.0.1:11223;127.0.0.1:11224"
	         transaction.transaction_mode="BATCH"/>
