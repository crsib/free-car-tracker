package me.crsib.freecartracker.data.repository

import kotlinx.coroutines.flow.Flow
import me.crsib.freecartracker.data.dao.VehicleDao
import me.crsib.freecartracker.data.entity.Vehicle

class VehicleRepository(private val dao: VehicleDao) {
    fun observeActive(): Flow<List<Vehicle>> = dao.observeActive()
    fun observeAll(): Flow<List<Vehicle>> = dao.observeAll()
    fun observe(id: Long): Flow<Vehicle?> = dao.observe(id)
    fun observeActiveCount(): Flow<Int> = dao.observeActiveCount()

    suspend fun getById(id: Long): Vehicle? = dao.getById(id)
    suspend fun add(vehicle: Vehicle): Long = dao.insert(vehicle)
    suspend fun update(vehicle: Vehicle) = dao.update(vehicle)
    suspend fun setArchived(vehicle: Vehicle, archived: Boolean) =
        dao.update(vehicle.copy(isArchived = archived))
    suspend fun delete(vehicle: Vehicle) = dao.delete(vehicle)
}
