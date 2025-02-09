[![Version](https://img.shields.io/maven-central/v/org.wildfly.clustering/wildfly-clustering-tomcat?style=for-the-badge&logo=redhat&logoColor=ee0000&label=latest)](https://search.maven.org/artifact/org.wildfly.clustering/wildfly-clustering-tomcat)
[![License](https://img.shields.io/github/license/wildfly-clustering/wildfly-clustering-tomcat?style=for-the-badge&color=darkgreen&logo=apache&logoColor=d22128)](https://www.apache.org/licenses/LICENSE-2.0)
[![Project Chat](https://img.shields.io/badge/zulip-chat-lightblue.svg?style=for-the-badge&logo=zulip&logoColor=ffffff)](https://wildfly.zulipchat.com/#narrow/stream/wildfly-clustering)

# wildfly-clustering-tomcat

High-availability session manager implementations for Tomcat based on WildFly's distributed session management using either an embedded Infinispan cache or a remote Infinispan server.

## Building

1.	Clone this repository and build for a specific Tomcat version using Java 17+ and a standard maven build.

		$ git clone git@github.com:wildfly-clustering/wildfly-clustering-tomcat.git
		$ cd wildfly-clustering-tomcat
		$ mvn clean install -P quickly -Dtomcat.version=11.0

> [!NOTE]
> The following Tomcat versions are supported:
>
> * 11.0 (Jakarta Servlet 6.1)
> * 10.1 (Jakarta Servlet 6.0)
> * 9.0 (Jakarta Servlet 4.0)

## Installation

1.	Copy the maven artifact containing the desired `Manager` implementation to Tomcat's `lib` directory:

		$ mvn --projects 11.0/infinispan/embedded dependency:copy -DoutputDirectory=$CATALINA_HOME/lib
	or:

		$ mvn --projects 11.0/infinispan/remote dependency:copy -DoutputDirectory=$CATALINA_HOME/lib

1.	Copy the runtime dependencies of the desired `Manager` implementation to Tomcat's `lib` directory:

		$ mvn --projects 11.0/infinispan/embedded dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=$CATALINA_HOME/lib
	or:

		$ mvn --projects 11.0/infinispan/remote dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=$CATALINA_HOME/lib

## Configuration

1. Define the distributed `<Manager/>` via its implementation class either within the global `$CATALINA_HOME/conf/context.xml`, or within the `/WEB-INF/context.xml` of a web application.
2. Ensure that your `<Engine/>` defines a `jvmRoute` attribute.[^1]  This is require to enable session affinity in Tomcat for use with load balancing.

[^1]: https://tomcat.apache.org/tomcat-11.0-doc/config/engine.html#Common_Attributes

### Embedded Infinispan Manager

	<Manager className="org.wildfly.clustering.tomcat.infinispan.embedded.InfinispanManager" .../>

### Remote Infinispan Manager

	<Manager className="org.wildfly.clustering.tomcat.infinispan.remote.HotRodManager" .../>

### Configuration Properties

|Property|Description|
|:---|:---|
|granularity|Defines how a session is mapped to entries in the cache. "SESSION" will store all attributes of a session in a single cache entry.  "ATTRIBUTE" will store each session attribute in a separate cache entry.  Default is "SESSION".|
|marshaller|Specifies the marshaller used to serialize and deserialize session attributes.  Supported marshallers include: JAVA, JBOSS, PROTOSTREAM.  Default marshaller is "JBOSS".|
|maxActiveSessions|Defines the maximum number of sessions to retain in local heap, after which the least recently used sessions will be evicted. The default behavior is implementation specific, see implementation specific properties for details.|

#### Common Manager properties

https://tomcat.apache.org/tomcat-11.0-doc/config/manager.html#Common_Attributes

#### Implementation specific properties

##### Embedded Infinispan Manager properties

|Property|Description|
|:---|:---|
|resource|Defines the location of the Infinispan configuration XML file, either as a classpath resource or as a filesystem path. Defaults to `infinispan.xml`|
|template|Defines the cache configuration from which a deployment cache will be created. By default, the default cache configuration will be used.|
|maxActiveSessions|Defines the maximum number of sessions to retain in local heap, after which the least recently used sessions will be evicted. When specified, this requires the use of a cache configuration with store configured for passivation[^2].  By default, local heap is unbounded.|

[^2]: https://infinispan.org/docs/stable/titles/configuring/configuring.html#passivation_persistence

##### Example

	<Manager className="org.wildfly.clustering.tomcat.infinispan.embedded.InfinispanManager"
	         resource="/path/to/infinispan.xml"
	         granularity="ATTRIBUTE"
	         marshaller="PROTOSTREAM"
	         maxActiveSessions="1000"
	         tcp_keep_alive="true"/>

##### Remote Infinispan Manager properties

|Property|Description|
|:---|:---|
|uri|Defines a HotRod URI, which includes a list of infinispan server instances and any authentication details.[^3]|
|template|Defines the server-side configuration template from which a deployment cache is created on the server. Default is `org.infinispan.DIST_SYNC`.|
|granularity|Defines how a session is mapped to entries in the cache. "SESSION" will store all attributes of a session in a single cache entry.  "ATTRIBUTE" will store each session attribute in a separate cache entry.  Default is "SESSION".|
|marshaller|Specifies the marshaller used to serialize and deserialize session attributes.  Supported marshallers include: JAVA, JBOSS, PROTOSTREAM.  Default marshaller is "JBOSS".|
|maxActiveSessions|Defines the maximum number of sessions to retain in the near cache, after which the least recently used sessions will be evicted. Near cache is disabled by default.|

[^3]: https://infinispan.org/blog/2020/05/26/hotrod-uri/

###### HotRod properties

These are configured without their "infinispan.client.hotrod." prefix:

https://github.com/infinispan/infinispan/blob/15.0.x/client/hotrod-client/src/main/java/org/infinispan/client/hotrod/impl/ConfigurationProperties.java


##### Example

	<Manager className="org.wildfly.clustering.tomcat.infinispan.remote.HotRodManager"
	         uri="hotrod://127.0.0.1:11222"
	         template="transactional"
	         granularity="ATTRIBUTE"
	         marshaller="PROTOSTREAM"
	         maxActiveSessions="100"
	         tcp_keep_alive="true"/>
