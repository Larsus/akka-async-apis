package info.gamlor.io

import akka.pattern._
import akka.dispatch.Await
import akka.util.duration._
import java.io.IOException
import akka.actor._
import java.util.concurrent.{TimeUnit, CountDownLatch}
import java.nio.file.StandardOpenOption
import akka.util.{ByteString, Timeout}
import info.gamlor.io.IOActors._
import akka.testkit.{TestProbe, TestActorRef}

/**
 * @author roman.stoffel@gamlor.info
 * @since 23.03.12
 */

class BasicIOActorSpec extends SpecBase {

  implicit val timeout = Timeout(5 seconds)

  describe("Actor IO") {

    it("allows to read a file") {

      val actor = IOActors.createForFile(TestFiles.inTestFolder("helloWorld.txt"))

      val readFuture = for {
        fileSize <- (actor ? FileSize).mapTo[FileSizeResponse]
        dataRead <- (actor ? Read(0, fileSize.size.toInt)).mapTo[ReadResponse]
      } yield dataRead


      val content = Await.result(readFuture, 5 seconds)
      content.data.utf8String must be("Hello World")
      content.startPoint must be(0)
      content.amountToRead must be(11)

    }
    it("can read chunked") {
      val fileActor = IOActors.createForFile(TestFiles.inTestFolder("largerTestFile.txt"))

      val receiver = TestProbe()

      fileActor.!(ReadInChunks(0, Int.MaxValue, "test-request"))(receiver.ref)

      val msg = receiver.receiveOne(5 seconds).asInstanceOf[ReadInChunksResponse]

      msg.identification must be("test-request")
      msg.data.isInstanceOf[IO.Chunk] must be(true)


    }
    it("can read chunked small ifle") {
      val fileActor = IOActors.createForFile(TestFiles.inTestFolder("helloWorld.txt"))

      val receiver = TestProbe()

      fileActor.!(ReadInChunks(0, Int.MaxValue, "test-request"))(receiver.ref)

      val dataMsg = receiver.receiveOne(5 seconds).asInstanceOf[ReadInChunksResponse]
      val eofMsg = receiver.receiveOne(5 seconds).asInstanceOf[ReadInChunksResponse]

      dataMsg.identification must be("test-request")
      dataMsg.data.asInstanceOf[IO.Chunk].bytes.utf8String must be("Hello World")
      eofMsg.data.isInstanceOf[IO.EOF] must be (true)


    }
    it("can write") {
      val fileActor = IOActors.createForFile(TestFiles.tempFile(), Set(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ))


      val operations = for {
        w <- fileActor ? Write(ByteString("Hello World"), 0)
        fileSize <- (fileActor ? FileSize).mapTo[FileSizeResponse]
        dataRead <- (fileActor ? Read(0, fileSize.size.toInt)).mapTo[ReadResponse]
      } yield dataRead

      val dataRead = Await.result(operations, 5 seconds)
      dataRead.data.utf8String must be("Hello World")
    }
    it("can have multiple actors") {
      val smallFileReader = IOActors.createForFile(TestFiles.inTestFolder("helloWorld.txt"))
      val largeFileReader = IOActors.createForFile(TestFiles.inTestFolder("largerTestFile.txt"))


      val sizes = for {
        smallSize <- (smallFileReader ? FileSize).mapTo[FileSizeResponse].map(f => f.size)
        largeSize <- (largeFileReader ? FileSize).mapTo[FileSizeResponse].map(f => f.size)
      } yield (smallSize, largeSize)


      val (smallSize, largeSize) = Await.result(sizes, 5 seconds)
      smallSize should be < (largeSize)
    }
    it("closes resource after timeout") {
      val testRef = TestActorRef(new IOActor(ctx => FileIO.open(TestFiles.inTestFolder("helloWorld.txt"))(ctx), Some(1 milliseconds)))

      // send request to open channel
      val sizeRequest = (testRef ? FileSize)
      Await.ready(sizeRequest, 5 seconds)

      Thread.sleep(200)

      testRef.underlyingActor.isChannelClosed must be(true)
    }

    it("crashes on io issue") {
      val supervisor = TestActorRef(new TestSupervisor())

      val failingFileSizeRequest = for {
        fileHandlingActor <- (supervisor ? "non-existing-file.txt").mapTo[ActorRef]
        fileSize <- fileHandlingActor ? FileSize
      } yield fileSize

      supervisor.underlyingActor.waitForFailure.await(1000, TimeUnit.SECONDS)

      supervisor.underlyingActor.ioExceptionCounter must be(1)
    }
    it("reports issue during reads") {
      val supervisor = TestActorRef(new TestSupervisor())

      val failingChannel = FailingTestChannels.failingChannel(system.dispatcher)
      val failingFileSizeRequest = for {
        fileHandlingActor <- (supervisor ? failingChannel).mapTo[ActorRef]
        fileSize <- fileHandlingActor ? Read(0, 200)
      } yield fileSize

      supervisor.underlyingActor.waitForFailure.await(1000, TimeUnit.SECONDS)

      supervisor.underlyingActor.ioExceptionCounter must be(1)
    }
    it("can close channel") {
      val testRef = TestActorRef(new IOActor(ctx => FileIO.open(TestFiles.inTestFolder("helloWorld.txt"))(ctx)))

      val sizeAndClose = for {
        sizeRequest <- (testRef ? FileSize)
        close <- (testRef ? CloseChannel)

      } yield close

      Thread.sleep(200)

      testRef.underlyingActor.isChannelClosed must be(true)
    }

  }

  class TestSupervisor extends Actor {
    var ioExceptionCounter = 0;
    val waitForFailure = new CountDownLatch(1)

    override val supervisorStrategy = OneForOneStrategy(5, 5 seconds) {
      case _: IOException => {
        ioExceptionCounter += 1
        waitForFailure.countDown()
        SupervisorStrategy.Stop
      }
    }

    protected def receive = {
      case fileName: String => sender ! IOActors.createForFile(TestFiles.inTestFolder(fileName))(context)
      case channel: FileChannelIO => sender ! context.actorOf(Props(new IOActor(ctx => channel)))
      case CrashCount => sender ! ioExceptionCounter
    }


  }

  case object CrashCount

}
