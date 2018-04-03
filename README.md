# wildfly-clustering-tomcat
Integrates Tomcat with WildFly's distributed web session management

## Building

1.	Clone this repository and build using a standard maven build.

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
|persistenceStrategy|Defines how a session is mapped to entries in the cache. "COARSE" will store all attributes of a session in a single cache entry.  "FINE" will store each session attribute in a separate cache entry.  Default is "COARSE".|
|maxActiveSessions|Defines the maximum number of sessions to retain in the L1 cache. Default is limitless.|

#### HotRod properties
These are configured without their "infinispan.client.hotrod." prefix:

https://github.com/infinispan/infinispan/blob/9.2.x/client/hotrod-client/src/main/java/org/infinispan/client/hotrod/impl/ConfigurationProperties.java

#### Common Manager properties

https://tomcat.apache.org/tomcat-9.0-doc/config/cluster-manager.html#Common_Attributes

#### Example

	<Manager className="org.wildfly.clustering.tomcat.hotrod.HotRodManager"
	         persistenceStrategy="FINE"
	         maxActiveSessions="100"
	         server_list="127.0.0.1:11222;127.0.0.1:11223;127.0.0.1:11224"/>
