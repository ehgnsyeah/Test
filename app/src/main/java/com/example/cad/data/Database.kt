package com.example.cad.data

import android.content.Context
import androidx.room.*
import com.example.cad.model.CadEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cad_projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val timeCreated: Long,
    val timeModified: Long,
    val sceneDataJson: String // Serialized List<CadEntity>
)

class Converters {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val listType = Types.newParameterizedType(List::class.java, CadEntity::class.java)
    private val adapter = moshi.adapter<List<CadEntity>>(listType)

    @TypeConverter
    fun fromJson(json: String): List<CadEntity>? {
        return try {
            adapter.fromJson(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun toJson(list: List<CadEntity>): String {
        return try {
            adapter.toJson(list)
        } catch (e: Exception) {
            "[]"
        }
    }
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM cad_projects ORDER BY timeModified DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM cad_projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: Int): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProject(project: ProjectEntity): Long

    @Query("DELETE FROM cad_projects WHERE id = :id")
    suspend fun deleteProjectById(id: Int)
}

@Database(entities = [ProjectEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class CadDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile
        private var INSTANCE: CadDatabase? = null

        fun getDatabase(context: Context): CadDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CadDatabase::class.java,
                    "cad_modeler_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
