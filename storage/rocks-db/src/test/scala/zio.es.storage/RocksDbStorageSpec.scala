package zio.es.storage

import java.util.UUID

import zio.{ Task, ZIO }
import zio.es._
import zio.es.serializers.protobuf._
import zio.duration._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._
import org.{ rocksdb => jrocks }

//noinspection TypeAnnotation
object RocksDbStorageSpec extends DefaultRunnableSpec {
  jrocks.RocksDB.loadLibrary()

  implicit val serializer: SerializableEvent[JournalTestModel] = PBSerializer.serializer[JournalTestModel]

  private val serializationTest = test("Should find implicit serializer for event") {
    val item         = JournalTestModel("id-1", block = false)
    val ser          = implicitly[SerializableEvent[JournalTestModel]]
    val serBytes     = ser.toBytes(item)
    val deserialized = ser.fromBytes(serBytes)
    assert(deserialized)(equalTo(item))
  }

  //private val storePath = "testStoreDataRDB/store"

  private def buildTestAggregate: Task[AggregateBehaviour[JournalTestModel, Seq[JournalTestModel]]] =
    EventJournal.aggregate[JournalTestModel, Seq[JournalTestModel]](Seq.empty[JournalTestModel]) {
      case (s, e) => ZIO.effect(s :+ e)
    }

  private val createEmpty = testM("creates empty Aggregate") {
    tmpTestStore.use { store =>
      for {
        testAggregate <- buildTestAggregate
        created       <- store.create(UUID.randomUUID().toString, testAggregate)
        createdState  <- created.state
      } yield assert(createdState)(isEmpty)
    }
  }

  private val saveAndLoad = testM("saves and loads Aggregate") {
    val eventsSeq = Seq(
      JournalTestModel("id-1", block = false),
      JournalTestModel("id-2", block = true),
      JournalTestModel("id-3", block = true)
    )

    val entityId = UUID.randomUUID().toString
    tmpTestStore.use { store =>
      for {
        testAggregate <- buildTestAggregate
        aggregate     <- store.load[Seq[JournalTestModel]](entityId, testAggregate)
        _             <- aggregate.appendAll(eventsSeq)
        createdState  <- aggregate.state
        loaded        <- store.load(entityId, testAggregate)
        loadedState   <- loaded.state
      } yield assert(loadedState)(equalTo(eventsSeq)) && assert(createdState)(equalTo(eventsSeq))
    }
  }

  private def tmpTestStore = RocksDbStorage.tmpRdb

  private val timeoutDuration = 5.second
  val spec                    = suite("RocksDB Storage specs")(
    serializationTest @@ timeout(timeoutDuration),
    createEmpty @@ timeout(timeoutDuration),
    saveAndLoad @@ timeout(timeoutDuration)
  ) @@ parallel
}
