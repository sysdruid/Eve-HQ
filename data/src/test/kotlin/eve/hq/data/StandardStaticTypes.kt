package eve.hq.data

abstract class StandardStaticTypes : IStaticTypesTestSchema {
    init {
        categories().id(4).name("Material")
        groups().id(18).category_id(4).name("Mineral")
        types().id(34).group_id(18).name("Tritanium").published(true)
        types().id(35).group_id(18).name("Pyerite").published(true)
    }
}