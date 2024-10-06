package com.imyvm.villagerShop.apis

import com.imyvm.hoki.config.ConfigOption
import com.imyvm.hoki.config.HokiConfig
import com.imyvm.hoki.config.Option
import com.typesafe.config.Config


class ModConfig : HokiConfig("Imyvm_VillagerShop.conf") {
    companion object {
        @JvmField
        @ConfigOption
        val LANGUAGE = Option(
            "core.language",
            "en_us",
            "the display language of Imyvm villager shop plugin"
        ) { obj: Config, path: String? ->
            obj.getString(
                path
            )
        }

        @JvmField
        @ConfigOption
        val TAX_RESTOCK = Option(
            "core.tax.restock",
            0.01,
            "The tax rate players should pay when restocking."
        ) { obj: Config, path: String? ->
            obj.getDouble(
                path
            )
        }

        @JvmField
        @ConfigOption
        val DATABASE_TYPE = Option(
            "core.database.database",
            "H2",
            "Database type, support \"POSTGRESQL\" , \"MYSQL\" or \"H2\""
        ) { obj: Config, path: String? ->
            obj.getString(
                path
            )
        }

        @JvmField
        @ConfigOption
        val DATABASE_URL = Option(
            "core.database.url",
            "localhost:5432/imyvmvillagershop",
            "Database connection URL.\n" +
                    "If none are available, we will generate default H2 files in the world folder"
        ) { obj: Config, path: String? ->
            obj.getString(
                path
            )
        }

        @JvmField
        @ConfigOption
        val DATABASE_USER = Option(
            "core.database.username",
            "root",
            "Database username."
        ) { obj: Config, path: String? ->
            obj.getString(
                path
            )
        }

        @JvmField
        @ConfigOption
        val DATABASE_PASSWORD = Option(
            "core.database.password",
            "",
            "Database password."
        ) { obj: Config, path: String? ->
            obj.getString(
                path
            )
        }

        @JvmField
        @ConfigOption
        val DATABASE_MAXPOOLSIZE = Option(
            "core.database.maximumPoolSize",
            10,
            "Only available in mysql, used to adjust the size of the Hikari pool."
        ) { obj: Config, path: String? ->
            obj.getInt(
                path
            )
        }
    }
}
