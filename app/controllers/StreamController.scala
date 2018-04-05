package controllers

import javax.inject.Inject
import java.nio.file.{Files, Paths, NoSuchFileException}

// import scala.collection.immutable.Range
// import scala.concurrent.Await
import scala.concurrent.duration.Duration

// import org.apache.commons.csv.CSVFormat

// import play.api._
import play.api.mvc.{
  Action, Request, AnyContent, AbstractController, ControllerComponents}
// import play.api.http._
// import play.api.libs.iteratee._

import akka.NotUsed
import akka.event.Logging
import akka.actor.ActorSystem
import akka.stream.stage.{
  GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{
  Attributes, Inlet, Outlet, SourceShape, FlowShape}
import akka.stream.scaladsl.{
  FileIO, Source, Sink, GraphDSL, Merge, Flow}
import akka.util.ByteString
import akka.stream.alpakka.csv.scaladsl.{CsvParsing, CsvToMap}
// import akka.stream.alpakka.s3.scaladsl._


/** experiment with custom flow stage for in-memory processing of chunks
 */
class MyCSVStage extends GraphStage[FlowShape[ByteString, ByteString]] {

  private val in = Inlet[ByteString](Logging.simpleName(this) + ".in")
  private val out = Outlet[ByteString](Logging.simpleName(this) + ".out")
  override val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes) = 
    new GraphStageLogic(shape) with InHandler with OutHandler {

      setHandlers(in, out, this)
      var leftover = ByteString()

      override def onPush(): Unit = {
        val elem = leftover ++ grab(in)
        val endPosition = elem.lastIndexOf(10)
        val send = elem.slice(0, endPosition+1)
        leftover = elem.slice(endPosition+1, elem.size+1)
        push(out, send)
      }

      override def onPull(): Unit = {
        pull(in)
      }

      override def onUpstreamFinish(): Unit = {
        emit(out, leftover)
        completeStage()
      }
  }
}


/**
 * Stream CSV data according to requested stream segments and filters
 */
class StreamController @Inject(
  ) (cc: ControllerComponents) extends AbstractController(cc) {

  /**
   * Filter function to filter stream by query parameters
   */
  def myFilter(
    in: List[ByteString],
    query: List[Seq[ByteString]]): Boolean =
  {
    (0 until 4).foldLeft(true) {
      (agg, i) => {
        // exit early once agg false, not sure whether that makes sense
        if (agg == false) { return false }
        agg &&
          (query(i).isEmpty || query(i).foldLeft(false) { _ || in(i+1) == _ })
      }
    }
  }

  /**
   * 1. Normalize query parameters to accept different ways to represent
   * lists in urls: ?list=item1,item2 and ?list=item1&list=item2
   * 2. Set default values if query parameter is empty
   * 3. Convert to ByteString in accordance with Akka
   */
  def normalize(in: Seq[String]): Seq[ByteString] = {
    in.foldLeft(List[ByteString]()) { _ ++ _.split(",").map(ByteString(_)) }
  }

  /**
   * Extract query parameters from requests by keyword for further processing
   */
  def getValues(
    key: String,
    in: Map[String, Seq[String]]
  ) : Seq[ByteString] = {
    val values = in.get(key)
    values match {
      case Some(values) => normalize(values)
      case None => List()
    }
  }

  /**
   * Create a list of query values to be applied by the filter function. Query
   * values are mapped by list position.
   */
  def getFilterList(
    in: Request[AnyContent],
    keys: List[String]): List[Seq[ByteString]] = {
      keys.map(getValues(_, in.queryString))
  }

  /**
   * Format CSV output stream
   */
  def formatCsvLine(lst: List[ByteString]): ByteString = {
    lst.reduce(_ ++ ByteString(",") ++ _) ++ ByteString("\n")
  }

  /**
   * A play view that streams CSV data from file to download and applying 
   * filters.
   * TODO: decide in what scope helper functions should be placed.
   */
  def chunkedFromSource() = Action {

    implicit request: Request[AnyContent] =>
    /*implicit val system = ActorSystem("Test")
    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(system)
        .withInputBuffer(initialSize=1, maxSize=128)
        .withSyncProcessingLimit(4)
    )*/

    /**
     * S3 source seems to work, not yet connected to anything
     */
    /* val s3Client = S3Client()
    val (s3Source: Source[ByteString, NotUsed], metaData) = s3Client
      .download("unimpaired", "100000042.csv")
    */

    // s3Source.to(Sink.foreach(println(_))).run()
    /* println(Await.result(metaData, Duration("5 seconds")).contentLength) */

    /* FileIO.fromPath(Paths.get("dump/1000042.csv"))
      .recover({ case _: IllegalArgumentException => ByteString() })
      .to(Sink.foreach(println("here", _))).run() */

    /**
     * Keywords used for filtering, they will be matched by position in list
     */
    val keywords = List(
      "measurements", "variables", "years", "months")

    val filterList = getFilterList(request, keywords)

    /**
     * Set query before passing function to flow
     */
    def filterFunction(in: List[ByteString]): Boolean =
      myFilter(in: List[ByteString], filterList)


    /**
     *  construct the source
     */
    val flow = Flow[String]
      .flatMapConcat({

        val csvPieces = Flow.fromGraph(new MyCSVStage())
        comid =>
          /* val (s3Source: Source[ByteString, NotUsed], _) = s3Client
            .download("unimpaired", comid + ".csv") */
          FileIO.fromPath(Paths.get("dump/" + comid + ".csv"))
          /** Recover catches file-does-not-exist errors and passes an empty
           *  ByteString to downstream stages instead.
           */
            .recover({ case _: NoSuchFileException => ByteString() })
          /* s3Source */
            .via(csvPieces)
            .via(CsvParsing.lineScanner())
            .map(List(ByteString(comid)) ++ _)
            .filter(filterFunction)
            .map(formatCsvLine)
      })

    val source = Source.fromGraph(GraphDSL.create() {
      implicit builder =>
      import GraphDSL.Implicits._

      /**
       * Create list from query parameters and convert to immutable list
       */
      val list = getValues("segments", request.queryString)
        .map(_.utf8String)
        .toList

      val in1 = Source(list)
      /**
       * List stream source from index.csv file, when no specific files are
       * requested.
       * TODO: Test whether DirectoryIO from Alpakka can do this job
       * effectively.
       */
      val in2 = FileIO.fromPath(Paths.get("dump/index.csv"))
        .via(CsvParsing.lineScanner()).map(_(0).utf8String)
      val merge = builder.add(Merge[ByteString](2))
      // val bcast = builder.add(Broadcast[String](2)) 

      /**
       *  Connect graph: in2 only used if list from request is empty
       */
      in1 ~> flow ~> merge.in(0)
      in2.filter(_ => list.isEmpty) ~> flow ~> merge.in(1)

      SourceShape(merge.out)
    })

    /* val experimentalSource = FileIO.fromPath(Paths.get("dump/index.csv"))
        .via(CsvParsing.lineScanner()).map(_(0).utf8String)
        .flatMapConcat(comid => {
          FileIO.fromPath(Paths.get("dump/" + comid + ".csv"))
            .recover({ case _: NoSuchFileException => ByteString() })
            .via(Framing.delimiter(ByteString("\n"), 256, allowTruncation=true))
            .map(ByteString(comid + ",") ++ _ ++ ByteString("\n"))
        })
        // .filter(filterFunction)
        // .map(formatCsvLine) */

    /**
     * Sink flow to chunked HTTP response using Play framework
     * TODO: Switch to Akka http eventually
     */
    Ok.chunked(source) as "text/csv"
  }

}
