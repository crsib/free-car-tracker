package me.crsib.freecartracker.data.repository

import kotlinx.coroutines.flow.Flow
import me.crsib.freecartracker.data.dao.CategoryTotal
import me.crsib.freecartracker.data.dao.ChargeEntryDao
import me.crsib.freecartracker.data.dao.CostDao
import me.crsib.freecartracker.data.dao.DeletedLedgerEntryDao
import me.crsib.freecartracker.data.dao.ExpenseDao
import me.crsib.freecartracker.data.dao.FuelEntryDao
import me.crsib.freecartracker.data.entity.ChargeEntry
import me.crsib.freecartracker.data.entity.CostView
import me.crsib.freecartracker.data.entity.DeletedLedgerEntry
import me.crsib.freecartracker.data.entity.Expense
import me.crsib.freecartracker.data.entity.FuelEntry

/** Single entry point for fuel, charge and expense rows plus cost aggregation. */
class EntryRepository(
    private val fuelDao: FuelEntryDao,
    private val chargeDao: ChargeEntryDao,
    private val expenseDao: ExpenseDao,
    private val costDao: CostDao,
    private val tombstoneDao: DeletedLedgerEntryDao,
    /** Clock for deletion tombstones; injectable for tests. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Invoked after any ledger mutation so the WebDAV sync can be triggered. */
    private val onLedgerChanged: () -> Unit = {},
) {
    // Fuel
    fun observeFuel(vehicleId: Long): Flow<List<FuelEntry>> = fuelDao.observeForVehicle(vehicleId)
    fun observeFuelByOdometer(vehicleId: Long): Flow<List<FuelEntry>> =
        fuelDao.observeForVehicleByOdometer(vehicleId)
    suspend fun getFuel(id: Long): FuelEntry? = fuelDao.getById(id)
    suspend fun saveFuel(entry: FuelEntry): Long = fuelDao.upsert(entry).also { onLedgerChanged() }
    suspend fun deleteFuel(id: Long) {
        val entry = fuelDao.getById(id) ?: return
        fuelDao.deleteById(id)
        tombstone(entry.syncId, entry.vehicleId)
    }
    /** Most recent fuel entry by date, used to prefill the cost when adding a new one. */
    suspend fun latestFuel(vehicleId: Long): FuelEntry? = fuelDao.latest(vehicleId)

    // Charge
    fun observeCharge(vehicleId: Long): Flow<List<ChargeEntry>> = chargeDao.observeForVehicle(vehicleId)
    suspend fun getCharge(id: Long): ChargeEntry? = chargeDao.getById(id)
    suspend fun saveCharge(entry: ChargeEntry): Long = chargeDao.upsert(entry).also { onLedgerChanged() }
    suspend fun deleteCharge(id: Long) {
        val entry = chargeDao.getById(id) ?: return
        chargeDao.deleteById(id)
        tombstone(entry.syncId, entry.vehicleId)
    }
    /** Most recent charge entry by date, used to prefill the price when adding a new one. */
    suspend fun latestCharge(vehicleId: Long): ChargeEntry? = chargeDao.latest(vehicleId)

    // Expense
    fun observeExpenses(vehicleId: Long): Flow<List<Expense>> = expenseDao.observeForVehicle(vehicleId)
    suspend fun getExpense(id: Long): Expense? = expenseDao.getById(id)
    suspend fun saveExpense(entry: Expense): Long = expenseDao.upsert(entry).also { onLedgerChanged() }
    suspend fun deleteExpense(id: Long) {
        val entry = expenseDao.getById(id) ?: return
        expenseDao.deleteById(id)
        tombstone(entry.syncId, entry.vehicleId)
    }

    /** Record a deletion tombstone (so the sync propagates it) and trigger a sync. */
    private suspend fun tombstone(syncId: String, vehicleId: Long) {
        tombstoneDao.upsert(DeletedLedgerEntry(syncId = syncId, vehicleId = vehicleId, deletedAtMillis = now()))
        onLedgerChanged()
    }

    /** Highest odometer reading recorded across fuel, charge and expense rows (canonical km). */
    suspend fun lastOdometerKm(vehicleId: Long): Double? =
        listOfNotNull(
            fuelDao.maxOdometer(vehicleId),
            chargeDao.maxOdometer(vehicleId),
            expenseDao.maxOdometer(vehicleId),
        ).maxOrNull()

    // Cost aggregation
    fun observeCostsSince(vehicleId: Long, sinceMillis: Long): Flow<List<CostView>> =
        costDao.observeSince(vehicleId, sinceMillis)
    fun observeTotalSince(vehicleId: Long, sinceMillis: Long): Flow<Long> =
        costDao.observeTotalSince(vehicleId, sinceMillis)
    fun observeByBucketSince(vehicleId: Long, sinceMillis: Long): Flow<List<CategoryTotal>> =
        costDao.observeByBucketSince(vehicleId, sinceMillis)
}
