package me.crsib.freecartracker.data.repository

import kotlinx.coroutines.flow.Flow
import me.crsib.freecartracker.data.dao.CategoryTotal
import me.crsib.freecartracker.data.dao.ChargeEntryDao
import me.crsib.freecartracker.data.dao.CostDao
import me.crsib.freecartracker.data.dao.ExpenseDao
import me.crsib.freecartracker.data.dao.FuelEntryDao
import me.crsib.freecartracker.data.entity.ChargeEntry
import me.crsib.freecartracker.data.entity.CostView
import me.crsib.freecartracker.data.entity.Expense
import me.crsib.freecartracker.data.entity.FuelEntry

/** Single entry point for fuel, charge and expense rows plus cost aggregation. */
class EntryRepository(
    private val fuelDao: FuelEntryDao,
    private val chargeDao: ChargeEntryDao,
    private val expenseDao: ExpenseDao,
    private val costDao: CostDao,
) {
    // Fuel
    fun observeFuel(vehicleId: Long): Flow<List<FuelEntry>> = fuelDao.observeForVehicle(vehicleId)
    fun observeFuelByOdometer(vehicleId: Long): Flow<List<FuelEntry>> =
        fuelDao.observeForVehicleByOdometer(vehicleId)
    suspend fun getFuel(id: Long): FuelEntry? = fuelDao.getById(id)
    suspend fun saveFuel(entry: FuelEntry): Long = fuelDao.upsert(entry)
    suspend fun deleteFuel(id: Long) = fuelDao.deleteById(id)

    // Charge
    fun observeCharge(vehicleId: Long): Flow<List<ChargeEntry>> = chargeDao.observeForVehicle(vehicleId)
    suspend fun getCharge(id: Long): ChargeEntry? = chargeDao.getById(id)
    suspend fun saveCharge(entry: ChargeEntry): Long = chargeDao.upsert(entry)
    suspend fun deleteCharge(id: Long) = chargeDao.deleteById(id)

    // Expense
    fun observeExpenses(vehicleId: Long): Flow<List<Expense>> = expenseDao.observeForVehicle(vehicleId)
    suspend fun getExpense(id: Long): Expense? = expenseDao.getById(id)
    suspend fun saveExpense(entry: Expense): Long = expenseDao.upsert(entry)
    suspend fun deleteExpense(id: Long) = expenseDao.deleteById(id)

    // Cost aggregation
    fun observeCostsSince(vehicleId: Long, sinceMillis: Long): Flow<List<CostView>> =
        costDao.observeSince(vehicleId, sinceMillis)
    fun observeTotalSince(vehicleId: Long, sinceMillis: Long): Flow<Long> =
        costDao.observeTotalSince(vehicleId, sinceMillis)
    fun observeByBucketSince(vehicleId: Long, sinceMillis: Long): Flow<List<CategoryTotal>> =
        costDao.observeByBucketSince(vehicleId, sinceMillis)
}
