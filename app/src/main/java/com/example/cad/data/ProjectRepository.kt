package com.example.cad.data

import com.example.cad.model.CadEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow

class ProjectRepository(private val projectDao: ProjectDao) {

    val allProjects: Flow<List<ProjectEntity>> = projectDao.getAllProjects()

    suspend fun getProjectById(id: Int): ProjectEntity? {
        return projectDao.getProjectById(id)
    }

    suspend fun saveProject(id: Int, name: String, entities: List<CadEntity>): Long {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val listType = Types.newParameterizedType(List::class.java, CadEntity::class.java)
        val adapter = moshi.adapter<List<CadEntity>>(listType)
        val json = adapter.toJson(entities) ?: "[]"

        val time = System.currentTimeMillis()
        val entity = ProjectEntity(
            id = if (id > 0) id else 0,
            name = name,
            timeCreated = if (id > 0) {
                // Keep previous or write current
                getProjectById(id)?.timeCreated ?: time
            } else time,
            timeModified = time,
            sceneDataJson = json
        )
        return projectDao.saveProject(entity)
    }

    suspend fun deleteProject(id: Int) {
        projectDao.deleteProjectById(id)
    }
}
