# Akka HTTP Client on Steroids

### Overview

`squbs-httpclient` project adds operationalization aspects to [Akka HTTP Host-Level Client-Side API](http://doc.akka.io/docs/akka-http/current/scala/http/client-side/host-level.html) while keeping the Akka HTTP API.  Here is the list of features it adds:

* [Service Discovery](#service-discovery-chain): Lets any service discovery mechanism to be plugged in and allows resolving HTTP endpoints by string identifiers, e.g., `paymentserv`.
* [Per Client configuration](#per-client-configuration): Let's each client to individually override defaults in `application.conf`.
* [Pipeline](#pipeline): Allows a `Bidi`Akka Streams flow to be registered globally or individually for clients.
* [Metrics](#metrics): Provides [Codahale Metrics](http://metrics.dropwizard.io/3.1.0/getting-started/) out-of-the-box for each client.    
* [JMX Beans](#jmx-beans): Exposes the configuration of each client as JMX beans.
* [Circuit Breaker](#circuit-breaker): // TODO In progress

### Dependency

Add the following dependency to your `build.sbt` or scala build file:

```
"org.squbs" %% "squbs-httpclient" % squbsVersion
```

### Usage

`squbs-httpclient` project sticks to the Akka HTTP API.  The only exception is during the creation of host connection pool.  Instead of `Http().cachedHostConnectionPool`, it defines `ClientFlow` with the same set of parameters (and few extra optional parameters).

#### Scala

Similar to the example at [Akka HTTP Host-Level Client-Side API](http://doc.akka.io/docs/akka-http/current/scala/http/client-side/host-level.html#example), the Scala use of `ClientFlow` is as follows:      
  

```scala
implicit val system = ActorSystem()
implicit val materializer = ActorMaterializer()
// construct a pool client flow with context type `Int`
val poolClientFlow = ClientFlow[Int]("sample") // Only this line is specific to squbs

val responseFuture: Future[(Try[HttpResponse], Int)] =
  Source.single(HttpRequest(uri = "/") -> 42)
    .via(poolClientFlow)
    .runWith(Sink.head)
```

#### Java

Also, similar to the example at [Akka HTTP Host-Level Client-Side API](http://doc.akka.io/docs/akka-http/current/java/http/client-side/host-level.html#example), the Java use of `ClientFlow` is as follows:

```java
final ActorSystem system = ActorSystem.create();
final ActorMaterializer mat = ActorMaterializer.create(system);

final Flow<Pair<HttpRequest, Integer>, Pair<Try<HttpResponse>, Integer>, HostConnectionPool>
    clientFlow = ClientFlow.create("sample", system, mat);

CompletionStage<Pair<Try<HttpResponse>, Integer>> =
    Source
        .single(Pair.create(request, 42))
        .via(clientFlow)
        .runWith(Sink.head(), mat);
```

#### HTTP Model

##### Scala

Below is an `HttpRequest` creation example in Scala.  Please see [HTTP Model Scala documentation](http://doc.akka.io/docs/akka-http/current/scala/http/common/http-model.html) for more details:

```scala
import HttpProtocols._
import MediaTypes._
import HttpCharsets._
val userData = ByteString("abc")
val authorization = headers.Authorization(BasicHttpCredentials("user", "pass"))

HttpRequest(
  PUT,
  uri = "/user",
  entity = HttpEntity(`text/plain` withCharset `UTF-8`, userData),
  headers = List(authorization),
  protocol = `HTTP/1.0`)
```
##### Java

Below is an `HttpRequest` creation example in Java.  Please see [Http Model Java documentation](http://doc.akka.io/docs/akka-http/current/java/http/http-model.html) for more details:

```java
import HttpProtocols.*;
import MediaTypes.*;

Authorization authorization = Authorization.basic("user", "pass");
HttpRequest complexRequest =
    HttpRequest.PUT("/user")
        .withEntity(HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, "abc"))
        .addHeader(authorization)
        .withProtocol(HttpProtocols.HTTP_1_0);
```

### Service Discovery Chain

`squbs-httpclient` does not require hostname/port combination to be provided during client pool creation.  Instead it allows a service discovery chain to be registered which allows resolving `HttpEndpoint`s by a string identifier by running through the registered service discovery mechanisms.  For instance, in the above example, `"sample"` is the logical name of the service that client wants to connect, the configured service discovery chain will resolve it to an `HttpEndpoint` which includes host and port, e.g., `http://akka.io:80`.

Please note, you can still pass a valid http URI as a string to `ClientFlow` as a default resolver to resolve valid http URIs is pre-registered in the service discovery chain by default:

   * `ClientFlow[Int]("http://akka.io")` in Scala
   * `ClientFlow.create("http://akka.io", system, mat)` in Java

There are two variations of registering resolvers as shown below. The closure style allows more compact and readable code. However, the subclass has the power to keep state and make resolution decisions based on such state:

##### Scala

Register function type `(String, Env) => Option[HttpEndpoint]`:

```scala
ResolverRegistry(system).register[HttpEndpoint]("SampleEndpointResolver", { (svcName, env) =>
  svcName match {
    case "sample" => Some(HttpEndpoint("http://akka.io:80"))
    case "google" => Some(HttpEndpoint("http://www.google.com:80"))
    case _ => None
})
```

Register class extending `Resolver[HttpEndpoint]`:

```scala
class SampleEndpointResolver extends Resolver[HttpEndpoint] {
  override def name: String = "SampleEndpointResolver"

  override def resolve(svcName: String, env: Environment): Option[HttpEndpoint] =
    svcName match {
      case "sample" => Some(Endpoint("http://akka.io:80"))
      case "google" => Some(Endpoint("http://www.google.com:80"))
      case _ => None
    }
}

// Register EndpointResolver
ResolverRegistry(system).register[HttpEndpoint](new SampleEndpointResolver)
```

##### Java

Register `BiFunction<String, Env, Optional<HttpEndpoint>>`:

```java
ResolverRegistry.get(system).register(HttpEndpoint.class, "SampleEndpointResolver", (svcName, env) -> {
    if ("sample".equals(svcName)) {
        return Optional.of(HttpEndpoint.create("http://akka.io:80"));
    } else if ("google".equals(svcName))
        return Optional.of(HttpEndpoint.create("http://www.google.com:80"));
    } else {
        return Optional.empty();
    }
});

```

Register class extending `AbstractResolver<HttpEndpoint>`:

```java
class SampleEndpointResolver extends AbstractResolver<HttpEndpoint> {
    String name() {
        return "SampleEndpointResolver";
    }

    Optional<HttpEndpoint> resolve(svcName: String, env: Environment) { 
        if ("sample".equals(svcName)) {
        return Optional.of(Endpoint.create("http://akka.io:80"));
    } else if ("google".equals(svcName))
        return Optional.of(Endpoint.create("http://www.google.com:80"));
    } else {
        return Optional.empty();
    }
}

// Register EndpointResolver
ResolverRegistry.get(system).register(HttpEndpoint.class, new SampleEndpointResolver());
```

You can register multiple `Resolver`s.  The chain is executed in the reverse order of registration.  If a resolver returns `None`, it means it could not resolve it and the next resolver is tried.  

If the resolved endpoint is a secure one, e.g., https, an `SSLContext` can be passed to `HttpEndpoint` as an optional parameter.

An optional `Config` can also be passed to `HttpEndpoint` to override the default configuration.  However, the client specific configuration has always higher precendence over the passed in configuration.

Please see [Resource Resolution](resolver.md) for details on resolution in general.

### Per Client Configuration

[Akka HTTP Configuration](http://doc.akka.io/docs/akka-http/current/scala/http/configuration.html) defines the default values for configuration.  You can override these defaults in `application.conf`; however, that would affect all the clients.  To do a client specific override, Akka HTTP allows passing a `ConnectionPoolSettings` during `HostConnectionPool` flow creation. This is supported by squbs as well.

In addition to the above, squbs allows a client specific override in `application.conf`.  You just need to specify a configuration section with the client's name that has `type = squbs.httpclient`.  Then, you can specify any client configuration inside the section.  For instance, if we would like to override the `max-connections` setting only for the above `"sample"` client, but no other client, we can do it as follows: 

```
sample {
  type = squbs.httpclient
  
  akka.http.host-connection-pool {
    max-connections = 10
  }
}
```

### Pipeline

We often need to have common infrastructure functionality or organizational standards across different clients.  Such infrastructure includes, but is not limited to, logging, metrics collection, request tracing, authentication/authorization, tracking, cookie management, A/B testing, etc.  As squbs promotes separation of concerns, such logic would belong to infrastructure and not client implementation. The [squbs pipeline](pipeline.md) allows infrastructure to provide components installed into a client without client owner having to worry about such aspects.  Please see [squbs pipeline](pipeline.md) for more details.

Generally speaking, a pipeline is a Bidi Flow acting as a bridge in between squbs client and the Akka HTTP layer.  `squbs-httpclient` allows registering a Bidi Akka Streams flow globally for all clients (default pipeline) or for individual clients.  To register a client specific pipeline, set the `pipeline` configuration.  You can turn on/off the default pipeline via `defaultPipeline` setting (it is set to `on`, if not specified):   

```
sample {
  type = squbs.httpclient
  pipeline = metricsFlow
  defaultPipeline = on
}
```

Please see [squbs pipeline](pipeline.md) to find out how to create a pipeline and configure default pipeline.

### Metrics

squbs comes with pre-built [pipeline](#pipeline) elements for metrics collection and squbs activator templates sets those as default.  Accordingly, each squbs http client is enabled to collect [Codahale Metrics](http://metrics.dropwizard.io/3.1.0/getting-started/) out-of-the-box without any code change or configuration.  The following metrics are available on JMX by default:

   * Request Timer
   * Request Count Meter
   * A meter for each http response status code category: 2xx, 3xx, 4xx, 5xx
   * A meter for each exception type that was returned by `ClientFlow`.


You can access the `MetricRegistry` by `MetricsExtension(system).metrics`.  This allows you to create further meters, timers, histograms, etc or to pass it to a different type metrics reporter.

### JMX Beans

Visibility of the system configuration has utmost importance while trouble shooting an issue.  `squbs-httpclient` registers a JMX bean for each client.  The JMX bean exposes all the configuration, e.g., endpoint, host connection pool settings, etc.  The name of the bean is set as `org.squbs.configuration.${system.name}:type=squbs.httpclient,name=$name`.  So, if the actor system name is `squbs` and the client name is `sample`, then the name of the JMX bean would be `org.squbs.configuration.squbs:type=squbs.httpclient,name=sample`.

### Circuit Breaker

// TODO In progress