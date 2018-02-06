package commons.repositories

import commons.models.{Descending, IdMetaModel, Ordering, Property}
import slick.dbio.DBIO
import slick.jdbc.H2Profile.api.{DBIO => _, MappedTo => _, Rep => _, TableQuery => _, _}
import slick.lifted._

import scala.concurrent.ExecutionContext.Implicits._

trait BaseRepo[ModelId <: BaseId[Long], Model <: WithId[Long, ModelId], ModelTable <: IdTable[ModelId, Model]] {
  // lazy required to init table query with concrete mappingConstructor value
  lazy val query: TableQuery[ModelTable] = TableQuery[ModelTable](mappingConstructor)
  protected val mappingConstructor: Tag => ModelTable
  implicit protected val modelIdMapping: BaseColumnType[ModelId]
  protected val metaModelToColumnsMapping: Map[Property[_], (ModelTable) => Rep[_]]
  protected val metaModel: IdMetaModel

  def all: DBIO[Seq[Model]] = all(List(Ordering(metaModel.id, Descending)))

  def all(orderings: Seq[Ordering]): DBIO[Seq[Model]] = {
    if (orderings == null || orderings.isEmpty) all
    else orderings match {
      case Nil => all
      case _ =>
        // multiple sortBy calls are reversed comparing to SQLs order by clause
        val slickOrderings = orderings.map(toSlickOrderingSupplier).reverse

        var sortQuery = query.sortBy(slickOrderings.head)
        slickOrderings.tail.foreach(getSlickOrdering => {
          sortQuery = sortQuery.sortBy(getSlickOrdering)
        })

        sortQuery.result
    }
  }

  def insertAndGet(model: Model): DBIO[Model] = {
    require(model != null)

    insertAndGet(Seq(model))
      .map(_.head)
  }

  def insertAndGet(models: Iterable[Model]): DBIO[Seq[Model]] = {
    if (models == null && models.isEmpty) DBIO.successful(Seq.empty)
    else query.returning(query.map(_.id))
      .++=(models)
      .flatMap(ids => byIds(ids))
  }

  def byIds(modelIds: Iterable[ModelId]): DBIO[Seq[Model]] = {
    if (modelIds == null || modelIds.isEmpty) DBIO.successful(Seq.empty)
    else query
      .filter(_.id inSet modelIds)
      .result
  }

  def insert(model: Model): DBIO[ModelId] = {
    require(model != null)

    insert(Seq(model))
      .map(_.head)
  }

  def insert(models: Iterable[Model]): DBIO[Seq[ModelId]] = {
    if (models != null && models.isEmpty) DBIO.successful(Seq.empty)
    else query.returning(query.map(_.id)).++=(models)
  }

  def updateAndGet(model: Model): DBIO[Model] = {
    require(model != null)

    query
      .filter(_.id === model.id)
      .update(model)
      .flatMap(_ => byId(model.id))
      .map(_.get)
  }

  def byId(modelId: ModelId): DBIO[Option[Model]] = {
    require(modelId != null)

    byIds(Seq(modelId))
      .map(_.headOption)
  }

  def delete(modelId: ModelId): DBIO[Int] = {
    require(modelId != null)

    delete(Seq(modelId))
  }

  def delete(ids: Seq[ModelId]): DBIO[Int] = {
    if (ids == null || ids.isEmpty) DBIO.successful(0)
    else query
      .filter(_.id inSet ids)
      .delete
  }

  protected def toSlickOrderingSupplier(ordering: Ordering): (ModelTable) => ColumnOrdered[_] = {
    implicit val Ordering(property, direction) = ordering
    val getColumn = metaModelToColumnsMapping(property)
    getColumn.andThen(RepoHelper.createSlickColumnOrdered)
  }

}

abstract class IdTable[Id <: BaseId[Long], Entity <: WithId[Long, Id]]
(tag: Tag, schemaName: Option[String], tableName: String)
(implicit val mapping: BaseColumnType[Id])
  extends Table[Entity](tag, schemaName, tableName) {

  protected val idColumnName: String = "id"

  def this(tag: Tag, tableName: String)(implicit mapping: BaseColumnType[Id]) = this(tag, None, tableName)

  final def id: Rep[Id] = column[Id](idColumnName, O.PrimaryKey, O.AutoInc)
}