-- IMPORTANT: Statements must be separated using triple dash then 'x',
-- because SQLiteDatabase.execSQL doesn't support executing multiple statements with one call.

CREATE TABLE routes (
   _id INTEGER PRIMARY KEY AUTOINCREMENT
	, vehicleType INTEGER NOT NULL
	, externalId TEXT NOT NULL
	, name TEXT NOT NULL
	, firstStopName TEXT NOT NULL
	, lastStopName TEXT NOT NULL

	, UNIQUE (vehicleType, externalId)
);

---x

CREATE TABLE updateTimes (
	  code TEXT NOT NULL PRIMARY KEY
  , lastUpdateTime INTEGER NOT NULL
);

