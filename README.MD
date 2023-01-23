Test Gradle Project to demonstrate the issue described in https://github.com/gradle/gradle/issues/23602

As delivered with Gradle 7.5.1, you can build just fine. 

    ./gradlew build

--> all compiles & tests OK. There are 2 integration test classes, one dummy `KafkaIT` with just one test, and the `MQIT` which spins up a working IBM MQ environment using TestContainer.

Now, checkout the branch named `gradle7.6` and see what happens...

    Unexpected exception thrown.
    org.gradle.internal.remote.internal.MessageIOException: Could not read message from '/127.0.0.1:62042'.
    at org.gradle.internal.remote.internal.inet.SocketConnection.receive(SocketConnection.java:94)
    at org.gradle.internal.remote.internal.hub.MessageHub$ConnectionReceive.run(MessageHub.java:270)
    at org.gradle.internal.concurrent.ExecutorPolicy$CatchAndRecordFailures.onExecute(ExecutorPolicy.java:64)
    at org.gradle.internal.concurrent.ManagedExecutorImpl$1.run(ManagedExecutorImpl.java:49)
    at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1128)
    at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:628)
    at java.base/java.lang.Thread.run(Thread.java:829)
    Caused by: java.lang.IllegalArgumentException
    at org.gradle.internal.remote.internal.hub.InterHubMessageSerializer$MessageReader.read(InterHubMessageSerializer.java:72)
    at org.gradle.internal.remote.internal.hub.InterHubMessageSerializer$MessageReader.read(InterHubMessageSerializer.java:52)
    at org.gradle.internal.remote.internal.inet.SocketConnection.receive(SocketConnection.java:81)
    ... 6 more

One of the tests somehow makes the communication break and Gradle thinks a test was skipped even though we have a horrible break and all subsequent tests are not even executed...