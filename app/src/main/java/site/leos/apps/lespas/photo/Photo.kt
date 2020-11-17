package site.leos.apps.lespas.photo

import android.os.Parcelable
import androidx.room.*
import androidx.room.ForeignKey.CASCADE
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.flow.Flow
import site.leos.apps.lespas.BaseDao
import site.leos.apps.lespas.album.Album
import java.util.*

@Entity(
    tableName = Photo.TABLE_NAME,
    indices = [Index(value = ["albumId"]), Index(value = ["dateTaken"])],
    foreignKeys = [ForeignKey(entity = Album::class, parentColumns = arrayOf("id"), childColumns = arrayOf("albumId"), onDelete = CASCADE)]
)
@Parcelize
data class Photo(
    @PrimaryKey var id: String,
    var albumId: String,
    var name: String,
    var eTag: String,
    var dateTaken: Date?,
    var lastModified: Date?,
    var shareId: Int) : Parcelable {
    companion object {
        const val TABLE_NAME = "photos"
    }
}

data class PhotoETag(val id: String, val eTag: String)
data class PhotoName(val id: String, val name: String)

@Dao
abstract class PhotoDao: BaseDao<Photo>() {
    @Query("DELETE FROM ${Photo.TABLE_NAME}")
    abstract suspend fun deleteAll()

    @Query("DELETE FROM ${Photo.TABLE_NAME} WHERE id = :photoId")
    abstract suspend fun deleteById(photoId: String): Int

    @Query("DELETE FROM ${Photo.TABLE_NAME} WHERE id = :photoId")
    abstract fun deleteByIdSync(photoId: String): Int

    @Query("SELECT id, eTag FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId")
    abstract fun getETagsMap(albumId: String): List<PhotoETag>

    @Query("SELECT id, name FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId")
    abstract fun getNamesMap(albumId: String): List<PhotoName>

    @Query("UPDATE ${Photo.TABLE_NAME} SET name = :newName WHERE id = :id")
    abstract fun changeName(id: String, newName: String)

    @Query("SELECT * FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId ORDER BY dateTaken ASC")
    abstract fun getAlbumPhotosByDateTakenASC(albumId: String): Flow<List<Photo>>

    @Query("SELECT COUNT(*) FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId")
    abstract fun getAlbumSize(albumId: String): Flow<Int>
}

/**
 * LiveData that propagates only distinct emissions.
fun <T> LiveData<T>.getDistinct(): LiveData<T> {
val distinctLiveData = MediatorLiveData<T>()
distinctLiveData.addSource(this, object : Observer<T> {
private var initialized = false
private var lastObj: T? = null

override fun onChanged(obj: T?) {
if (!initialized) {
initialized = true
lastObj = obj
distinctLiveData.postValue(lastObj)
} else if ((obj == null && lastObj != null) || obj != lastObj) {
lastObj = obj
distinctLiveData.postValue(lastObj)
}
}
})

return distinctLiveData
 */
