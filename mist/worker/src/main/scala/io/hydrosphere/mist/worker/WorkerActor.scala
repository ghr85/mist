package io.hydrosphere.mist.worker

import akka.actor._
import akka.pattern.pipe
import io.hydrosphere.mist.core.CommonData._
import io.hydrosphere.mist.worker.logging.{LogsWriter, RemoteAppender}
import io.hydrosphere.mist.worker.runners._
import mist.api.data.JsLikeData
import org.apache.log4j.{Appender, LogManager}
import org.apache.spark.streaming.StreamingContext

class WorkerActor(
  runnerSelector: RunnerSelector,
  namedContext: MistScContext,
  artifactDownloader: ArtifactDownloader,
  mkAppender: String => Option[Appender]
) extends Actor with ActorLogging {

  import context._

  val sparkRootLogger = LogManager.getRootLogger

  type JobFuture = CancellableFuture[JsLikeData]

  override def receive: Receive = awaitRequest()

  private def awaitRequest(): Receive = {
    case req: RunJobRequest =>
      mkAppender(req.id).foreach(sparkRootLogger.addAppender)
      sender() ! JobFileDownloading(req.id)
      artifactDownloader.downloadArtifact(req.params.filePath) pipeTo self
      context become downloading(sender(), req, running)

    case _: ShutdownCommand =>
      self ! PoisonPill
  }

  private def downloading(
    respond: ActorRef,
    req: RunJobRequest,
    next: (RunJobRequest, ActorRef, JobFuture) => Receive
  ): Receive = {
    case artifact: SparkArtifact =>
      val runner = runnerSelector.selectRunner(artifact)
      namedContext.sc.setJobGroup(req.id, req.id)
      val jobFuture = run(runner, req)
      respond ! JobStarted(req.id)
      jobFuture.future pipeTo self
      context become next(req, respond, jobFuture)

    case CancelJobRequest(id) =>
      sparkRootLogger.removeAppender(id)
      respond ! JobIsCancelled(id)
      respond ! mkFailure(req, new RuntimeException("Job was cancelled before starting"))
      context become awaitRequest()

    case ForceShutdown =>
      respond ! mkFailure(req, "Worker has been stopped")
      self ! PoisonPill

    case CompleteAndShutdown =>
      context become downloading(respond, req, completeAndShutdown)

    case Status.Failure(e) =>
      respond ! mkFailure(req, e)
      context become awaitRequest()
  }

  private def running(req: RunJobRequest, respond: ActorRef, jobFuture: JobFuture): Receive = {
    case RunJobRequest(id, _, _) =>
      sender() ! WorkerIsBusy(id)

    case data: JsLikeData =>
      log.info(s"Job execution done. Returning result $data and become awaiting new request")
      sparkRootLogger.removeAppender(req.id)
      respond ! JobSuccess(req.id, data)
      context become awaitRequest()

    case Status.Failure(e) =>
      log.info(s"Job execution done. Returning result $e and become awaiting new request")
      sparkRootLogger.removeAppender(req.id)
      respond ! mkFailure(req, e)
      context become awaitRequest()

    case CancelJobRequest(id) =>
      cancel(id, sender(), jobFuture)

    case ForceShutdown =>
      self ! PoisonPill

    case CompleteAndShutdown =>
      context become completeAndShutdown(req, respond, jobFuture)
  }

  private def completeAndShutdown(req: RunJobRequest, respond: ActorRef, jobFuture: JobFuture): Receive = {
    case CancelJobRequest(id) =>
      cancel(id, sender(), jobFuture)

    case data: JsLikeData =>
      log.info(s"Job execution done. Returning result $data and become awaiting new request")
      sparkRootLogger.removeAppender(req.id)
      respond ! JobSuccess(req.id, data)
      self ! PoisonPill

    case Status.Failure(e) =>
      log.info(s"Job execution done. Returning result $e and become awaiting new request")
      sparkRootLogger.removeAppender(req.id)
      respond ! mkFailure(req, e)
      self ! PoisonPill
  }

  private def cancel(id: String, respond: ActorRef, jobFuture: JobFuture): Unit = {
    sparkRootLogger.removeAppender(id)
    namedContext.sc.cancelJobGroup(id)
    StreamingContext.getActive().foreach( _.stop(stopSparkContext = false, stopGracefully = true))
    jobFuture.cancel()
    respond ! JobIsCancelled(id)
  }

  override def postStop(): Unit = {
    artifactDownloader.stop()
  }

  private def run(runner: JobRunner, req: RunJobRequest): JobFuture = CancellableFuture.onDetachedThread {
    runner.run(req, namedContext) match {
      case Left(e: InterruptedException) => throw new RuntimeException("Execution was cancelled")
      case Left(e) => throw e
      case Right(data) => data
    }
  }

  private def mkFailure(req: RunJobRequest, ex: Throwable): JobResponse =
    JobFailure(req.id, buildErrorMessage(req.params, ex))

  private def mkFailure(req: RunJobRequest, ex: String): JobResponse =
    JobFailure(req.id, ex)

  protected def buildErrorMessage(params: JobParams, e: Throwable): String = {
    val msg = Option(e.getMessage).getOrElse("")
    val trace = e.getStackTrace.map(e => e.toString).mkString("; ")
    s"Error running job with $params. Type: ${e.getClass.getCanonicalName}, message: $msg, trace $trace"
  }
}

object WorkerActor {

  def props(
    context: MistScContext,
    artifactDownloader: ArtifactDownloader,
    runnerSelector: RunnerSelector,
    mkAppender: String => Option[Appender]
  ): Props =
    Props(new WorkerActor(runnerSelector, context, artifactDownloader, mkAppender))

  def props(context: MistScContext, artifactDownloader: ArtifactDownloader, writer: Option[LogsWriter], runnerSelector: RunnerSelector): Props =
    props(context, artifactDownloader, runnerSelector, mkAppenderF(writer))

  def props(context: MistScContext, artifactDownloader: ArtifactDownloader, writer: Option[LogsWriter]): Props =
    props(context, artifactDownloader, writer, new SimpleRunnerSelector)

  def mkAppenderF(writer: Option[LogsWriter]): String => Option[Appender] =
    (id: String) => writer.map(w => new RemoteAppender(id, w))

}

