package eve.hq.data

import org.jdbscript.IDBSchema
import org.jdbscript.RecordTools
import java.time.Instant

@Suppress("FunctionName")
interface ICharacterTestSchema : IDBSchema {
    fun characters(): ICharacterRecord

    interface ICharacterRecord : IDBSchema.IDBRecord {
        fun id(value: Long): ICharacterRecord
        fun name(value: String): ICharacterRecord
        fun access_token(value: String): ICharacterRecord
        fun refresh_token(value: String): ICharacterRecord
        fun token_expires_at(value: Instant): ICharacterRecord
        fun status(value: String): ICharacterRecord
        fun invalid_reason(value: String?): ICharacterRecord

        fun defaults(tools: RecordTools) {
            access_token("access")
            refresh_token("refresh")
            token_expires_at(Instant.parse("2026-01-01T00:00:00Z"))
        }
    }
}