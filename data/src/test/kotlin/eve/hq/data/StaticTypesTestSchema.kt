package eve.hq.data

import org.jdbscript.IDBSchema

@Suppress("FunctionName")
interface IStaticTypesTestSchema : IDBSchema {
    fun categories(): ICategoryRecord
    fun groups(): IGroupRecord
    fun types(): ITypeRecord

    interface ICategoryRecord : IDBSchema.IDBRecord {
        fun id(value: Long): ICategoryRecord
        fun name(value: String): ICategoryRecord
    }

    interface IGroupRecord : IDBSchema.IDBRecord {
        fun id(value: Long): IGroupRecord
        fun category_id(value: Long): IGroupRecord
        fun name(value: String): IGroupRecord
    }

    interface ITypeRecord : IDBSchema.IDBRecord {
        fun id(value: Long): ITypeRecord
        fun group_id(value: Long): ITypeRecord
        fun name(value: String): ITypeRecord
        fun published(value: Boolean): ITypeRecord
    }
}