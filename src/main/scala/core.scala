package net.kriomant.gortrans

object core {
	object VehicleType extends Enumeration {
		val Bug = Value(0)
		val TrolleyBus = Value(1)
		val TramWay = Value(2)
		val MiniBus = Value(7)
	}

	case class Route(id: String, name: String, begin: String, end: String)

	type RoutesInfo = Map[VehicleType.Value, Seq[Route]]
}
