package me.crsib.freecartracker.data

import androidx.room.TypeConverter
import me.crsib.freecartracker.domain.model.ChargeType
import me.crsib.freecartracker.domain.model.ExpenseCategory
import me.crsib.freecartracker.domain.model.FuelType
import me.crsib.freecartracker.domain.model.ObdTransportKind
import me.crsib.freecartracker.domain.model.Powertrain
import me.crsib.freecartracker.domain.model.Recurrence
import me.crsib.freecartracker.domain.model.TripStatus
import me.crsib.freecartracker.domain.model.UnitSystem

/** Stores enums as their stable name() string. */
class Converters {
    @TypeConverter fun powertrainToString(v: Powertrain): String = v.name
    @TypeConverter fun stringToPowertrain(v: String): Powertrain = Powertrain.valueOf(v)

    @TypeConverter fun fuelTypeToString(v: FuelType?): String? = v?.name
    @TypeConverter fun stringToFuelType(v: String?): FuelType? = v?.let { FuelType.valueOf(it) }

    @TypeConverter fun chargeTypeToString(v: ChargeType): String = v.name
    @TypeConverter fun stringToChargeType(v: String): ChargeType = ChargeType.valueOf(v)

    @TypeConverter fun unitSystemToString(v: UnitSystem): String = v.name
    @TypeConverter fun stringToUnitSystem(v: String): UnitSystem = UnitSystem.valueOf(v)

    @TypeConverter fun categoryToString(v: ExpenseCategory): String = v.name
    @TypeConverter fun stringToCategory(v: String): ExpenseCategory = ExpenseCategory.valueOf(v)

    @TypeConverter fun recurrenceToString(v: Recurrence): String = v.name
    @TypeConverter fun stringToRecurrence(v: String): Recurrence = Recurrence.valueOf(v)

    @TypeConverter fun tripStatusToString(v: TripStatus): String = v.name
    @TypeConverter fun stringToTripStatus(v: String): TripStatus = TripStatus.valueOf(v)

    @TypeConverter fun obdTransportToString(v: ObdTransportKind?): String? = v?.name
    @TypeConverter fun stringToObdTransport(v: String?): ObdTransportKind? =
        v?.let { ObdTransportKind.valueOf(it) }
}
