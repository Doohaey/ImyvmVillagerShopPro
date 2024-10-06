package com.imyvm.villagerShop.commands

import com.imyvm.villagerShop.VillagerShopMain
import com.imyvm.villagerShop.apis.*
import com.imyvm.villagerShop.apis.ShopService.Companion.ShopType
import com.imyvm.villagerShop.apis.ShopService.Companion.rangeSearch
import com.imyvm.villagerShop.apis.Translator.tr
import com.imyvm.villagerShop.items.ItemManager
import com.imyvm.villagerShop.items.ItemManager.Companion.offerItemToPlayer
import com.imyvm.villagerShop.items.ItemManager.Companion.removeItemFromInventory
import com.imyvm.villagerShop.shops.ShopEntity
import com.imyvm.villagerShop.shops.ShopEntity.Companion.calculateAndTakeMoney
import com.imyvm.villagerShop.shops.ShopEntity.Companion.checkCanAddNewShop
import com.imyvm.villagerShop.shops.ShopEntity.Companion.checkCanAddTradeOffer
import com.imyvm.villagerShop.shops.ShopEntity.Companion.getDefaultShop
import com.imyvm.villagerShop.shops.ShopEntity.Companion.shopDBService
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg
import com.mojang.brigadier.arguments.DoubleArgumentType.getDouble
import com.mojang.brigadier.arguments.IntegerArgumentType.getInteger
import com.mojang.brigadier.arguments.IntegerArgumentType.integer
import com.mojang.brigadier.arguments.StringArgumentType.*
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import kotlinx.coroutines.cancel
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.command.argument.BlockPosArgumentType.blockPos
import net.minecraft.command.argument.BlockPosArgumentType.getBlockPos
import net.minecraft.command.argument.ItemStackArgumentType.getItemStackArgument
import net.minecraft.command.argument.ItemStackArgumentType.itemStack
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.ClickEvent
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

data class PendingOperation(val playerUuid: UUID, val operation: () -> Unit)
val pendingOperations = ConcurrentHashMap<UUID, PendingOperation>()
fun register(dispatcher: CommandDispatcher<ServerCommandSource>,
             registryAccess: CommandRegistryAccess) {
        val builder = literal("villagerShop")
        .requires(ServerCommandSource::isExecutedByPlayer)
            .then(literal("create")
                .then(literal("adminShop")
                    .requires(Permissions.require(VillagerShopMain.MOD_ID + ".admin", 3))
                    .then(argument("shopName", string())
                        .then(argument("pos", blockPos())
                            .then(argument("type", string())
                                .suggests { context, builder ->
                                    suggestOptions(builder, ShopType.entries.map { it.name })
                                }
                                .then(argument("items", greedyString())
                                .executes { context ->
                                    val newShop = ShopEntity(context, ShopType.valueOf(getString(context, "type")))
                                    newShop.adminShopCreate()
                                    context.source.player!!.sendMessage(tr("commands.shop.create.success"))
                                    newShop.spawnOrRespawn(context.source.world)
                                    1
                                }
                                )
                            )
                        )
                    )
                )
                .then(literal("shop")
                    .then(argument("shopName", string())
                        .then(argument("pos", blockPos())
                            .then(argument("item", itemStack(registryAccess))
                                .then(argument("quantitySoldEachTime", integer(1))
                                    .then(argument("price", doubleArg(0.1))
                                        .executes{ context ->
                                            val amount = checkCanAddNewShop(context)
                                            val player = context.source.player!!
                                            if (amount != 0L &&
                                                checkCanAddTradeOffer(
                                                    getDefaultShop(),
                                                    ItemManager(
                                                        getItemStackArgument(context, "item"),
                                                        getInteger(context, "quantitySoldEachTime"),
                                                        getDouble(context, "price"),
                                                        mutableMapOf<String, Int>(Pair("default", 0)),
                                                        context.source.registryManager
                                                    ),
                                                    player
                                                    )
                                                ) {
                                                val action = {
                                                    val newShop = ShopEntity(context)
                                                    calculateAndTakeMoney(
                                                        player,
                                                        amount
                                                    )
                                                    val stock = removeItemFromInventory(
                                                        player,
                                                        getItemStackArgument(context, "item").createStack(1, false),
                                                        getInteger(context, "quantitySoldEachTime")
                                                    )
                                                    newShop.addTradeOffer(
                                                        ItemManager(
                                                            getItemStackArgument(context, "item"),
                                                            getInteger(context, "quantitySoldEachTime"),
                                                            getDouble(context, "price"),
                                                            mutableMapOf<String, Int>(Pair("default", stock)),
                                                            context.source.registryManager
                                                        ),
                                                        player
                                                    )
                                                    newShop.playerShopCreate()
                                                    player.sendMessage(tr("commands.shop.create.success"))
                                                    newShop.spawnOrRespawn(context.source.world)
                                                }
                                                addPendingOperation(context, action)
                                            }
                                            1
                                        }
                                    )
                                )
                            )
                        )
                    )
                )
            )
            .then(literal("config")
                .requires(Permissions.require(VillagerShopMain.MOD_ID + ".admin", 3))
                .then(literal("taxRate")
                    .then(literal("set")
                        .then(argument("taxRate", doubleArg())
                            .executes { context ->
                                taxRateChange(
                                    context,
                                    getDouble(context, "taxRate")
                                )
                            }
                        )
                    )
                )
                .then(literal("reload")
                    .executes{ context ->
                        reload(
                            context
                        )
                    }
                )
            )
            .then(literal("manager")
                .then(literal("changeInfo")
                    .then(literal("setShopName")
                        .then(argument("shopNameOld", string())
                            .then(argument("shopNameNew", string())
                                .executes { context ->
                                    val player = context.source.player!!
                                    val shop = shopDBService.readByShopName(
                                        getString(context, "shopNameOld"),
                                        player.nameForScoreboard,
                                        context.source.registryManager
                                    ).singleOrNull()
                                    shop?.let {
                                        it.shopname = getString(context, "shopNameNew")
                                        it.update()
                                        context.source.player?.sendMessage(tr("commands.execute.success"))
                                    } ?: {
                                        context.source.player?.sendMessage(tr("commands.shops.none"))
                                    }
                                    1
                                }
                            )
                        )
                        .then(argument("id", integer())
                            .then(argument("shopNameNew", string())
                                .executes { context ->
                                    val player = context.source.player!!
                                    val shop = shopDBService.readById(
                                        getInteger(context, "id"),
                                        context.source.registryManager
                                    )
                                    shop?.let {
                                        it.shopname = getString(context, "shopNameNew")
                                        it.update()
                                        player.sendMessage(tr("commands.execute.success"))
                                    } ?: {
                                        player.sendMessage(tr("commands.shops.none"))
                                    }
                                    1
                                }
                            )
                        )
                    )
                    .then(literal("setShopPos")
                        .then(argument("id", integer())
                            .then(argument("newShopPos", blockPos())
                                .executes { context ->
                                    val player = context.source.player!!
                                    val shop = shopDBService.readById(
                                        getInteger(context, "id"),
                                        context.source.registryManager
                                    )
                                    val newPos = getBlockPos(context, "newShopPos")

                                    shop?.let {
                                        it.posX = newPos.x
                                        it.posY = newPos.y
                                        it.posZ = newPos.z
                                        shopDBService.update(it)
                                        player.sendMessage(tr("commands.execute.success"))
                                    } ?: {
                                        player.sendMessage(tr("commands.shops.none"))
                                    }
                                    1
                                }
                            )
                        )
                        .then(argument("shopName", string())
                            .then(argument("newShopPos", blockPos())
                                .executes { context ->
                                    val player = context.source.player!!
                                    val shop = shopDBService.readByShopName(
                                        getString(context, "shopName"),
                                        player.nameForScoreboard,
                                        context.source.registryManager
                                    ).singleOrNull()
                                    val newPos = getBlockPos(context, "newShopPos")

                                    shop?.let {
                                        it.posX = newPos.x
                                        it.posY = newPos.y
                                        it.posZ = newPos.z
                                        it.update()
                                        player.sendMessage(tr("commands.execute.success"))
                                    } ?: {
                                        player.sendMessage(tr("commands.shops.none"))
                                    }
                                    1
                                }
                            )
                        )
                    )
                )
                .then(literal("delete")
                    .then(argument("id", integer(1))
                        .requires(Permissions.require(VillagerShopMain.MOD_ID + ".manager",2))
                        .executes { context ->
                            val shop = shopDBService.readById(
                                getInteger(context,"id"),
                                context.source.registryManager
                            )
                            val player = context.source.player!!
                            shop?.let {
                                val action = {
                                    it.delete()
                                    player.sendMessage(tr("commands.deleteshop.ok"))
                                }
                                addPendingOperation(context, action)
                            } ?: {
                                player.sendMessage(tr("commands.shops.none"))
                            }
                            1
                        }
                    )
                    .then(argument("shopName",string())
                        .executes { context ->
                            val player = context.source.player!!
                            val shop = shopDBService.readByShopName(
                                getString(context,"shopName"),
                                player.nameForScoreboard,
                                context.source.registryManager
                            ).singleOrNull()
                            shop?.let {
                                val action = {
                                    offerItemToPlayer(player, it.items)
                                    it.delete()
                                    player.sendMessage(tr("commands.deleteshop.ok"))
                                }
                                addPendingOperation(context, action)
                            } ?: {
                                player.sendMessage(tr("commands.shops.none"))
                            }
                            1
                        }
                    )
                )
                .then(literal("search")
                    .requires(Permissions.require(VillagerShopMain.MOD_ID + ".manage",2))
                    .then(argument("searchCondition", greedyString())
                        .executes { context ->
                            rangeSearch(
                                context,
                                getString(context, "searchCondition")
                            )
                        }
                    )
                )
                .then(literal("inquire")
                    .requires(Permissions.require(VillagerShopMain.MOD_ID + ".manage",2))
                    .then(argument("id", integer(1))
                        .executes { context ->
                            val player = context.source.player!!
                            val shop = shopDBService.readById(
                                getInteger(context, "id"),
                                context.source.registryManager
                            )
                            if (shop == null) {
                                player.sendMessage(tr("commands.search.none"))
                            } else {
                                shop.info(player)
                            }
                            1
                        }
                    )
                )
                .then(literal("setAdmin")
                    .requires(Permissions.require(VillagerShopMain.MOD_ID + ".admin",3))
                    .then(argument("id", integer(1))
                        .executes { context ->
                            val shop = shopDBService.readById(
                                getInteger(context, "id"),
                                context.source.registryManager
                            )
                            shop?.let {
                                val action = {
                                    it.setAdmin()
                                    val textSupplier = Supplier<Text> { tr("commands.setadmin.ok") }
                                    context.source.sendFeedback(textSupplier,true)
                                }
                                addPendingOperation(context, action)
                            } ?: {
                                val textSupplier = Supplier<Text> { tr("commands.shops.none") }
                                context.source.sendFeedback(textSupplier,true)
                            }
                            1
                        }
                    )
                )
            )
            .then(literal("respawn")
                .requires(Permissions.require(VillagerShopMain.MOD_ID + ".admin",3))
                .then(argument("id", integer(1))
                    .executes{ context ->
                        val shop = shopDBService.readById(
                            getInteger(context, "id"),
                            context.source.registryManager
                        )
                        val world = context.source.world
                        val player = context.source.player!!
                        shop?.let {
                            if (world.registryKey.value.toString() == it.world) {
                                it.spawnOrRespawn(world)
                                player.sendMessage(tr("commands.execute.success"))
                            } else {
                                player.sendMessage(tr("commands.failed"))
                            }
                        } ?: {
                            player.sendMessage(tr("commands.search.none"))
                        }
                        1
                    }
                )
            )
            .then(literal("item")
                .then(literal("addStock")
                    .then(argument("shopName", string())
                        .then(argument("item", itemStack(registryAccess))
                            .executes { context ->
                                val player = context.source.player!!
                                val shop = shopDBService.readByShopName(
                                    getString(context, "shopName"),
                                    player.nameForScoreboard,
                                    context.source.registryManager
                                ).firstOrNull()
                                shop?.let {
                                    val tradeNeedChange = it.getTradedItem(getItemStackArgument(context, "item"))
                                    val stockToAdd = removeItemFromInventory(
                                        player,
                                        getItemStackArgument(context, "item").createStack(1, false),
                                        player.inventory.count(getItemStackArgument(context, "item").item)
                                    )
                                    tradeNeedChange?.let {
                                        it.stock["default"]?.let { it1 -> it.stock["default"] = it1 + stockToAdd }
                                    } ?: {
                                        player.sendMessage(tr("commands.shop.create.no_item"))
                                    }
                                    it.update()
                                } ?: {
                                    player.sendMessage(tr("commands.shops.none"))
                                }
                                1
                            }
                            .then(argument("quantitySoldEachTime", integer(1))
                                .executes { context ->
                                    val player = context.source.player!!
                                    val shop = shopDBService.readByShopName(
                                        getString(context, "shopName"),
                                        player.nameForScoreboard,
                                        context.source.registryManager
                                    ).firstOrNull()
                                    shop?.let {
                                        val tradeNeedChange = it.getTradedItem(getItemStackArgument(context, "item"))
                                        val stockToAdd = removeItemFromInventory(
                                            player,
                                            getItemStackArgument(context, "item").createStack(1, false),
                                            getInteger(context, "quantitySoldEachTime")
                                        )
                                        tradeNeedChange?.let {
                                            it.stock["default"]?.let { it1 -> it.stock["default"] = it1 + stockToAdd }
                                        } ?: {
                                            player.sendMessage(tr("commands.shop.create.no_item"))
                                        }
                                        it.update()
                                    } ?: {
                                        player.sendMessage(tr("commands.shops.none"))
                                    }
                                    1
                                }
                            )
                        )
                    )
                )
                .then(literal("add")
                    .then(argument("shopName", string())
                        .then(argument("item", itemStack(registryAccess))
                            .then(argument("quantitySoldEachTime", integer(1))
                                .then(argument("price", doubleArg(0.1))
                                    .executes { context ->
                                        val newTradedItem = ItemManager(
                                            getItemStackArgument(context, "item"),
                                            getInteger(context, "quantitySoldEachTime"),
                                            getDouble(context, "price"),
                                            registries = context.source.registryManager
                                        )
                                        val player = context.source.player!!

                                        val shop = shopDBService.readByShopName(
                                            getString(context, "shopName"),
                                            player.nameForScoreboard,
                                            context.source.registryManager
                                        ).firstOrNull()
                                        shop?.let {
                                            if (checkCanAddTradeOffer(it, newTradedItem, player)) {
                                                it.addTradeOffer(newTradedItem, player)
                                            }
                                        } ?: {
                                            player.sendMessage(tr("commands.shops.none"))
                                        }
                                        1
                                    }
                                )
                            )
                        )
                    )
                )
                .then(literal("delete")
                    .then(argument("shopName", string())
                        .then(argument("item", itemStack(registryAccess))
                            .executes {context ->
                                val player = context.source.player!!
                                val shop = shopDBService.readByShopName(
                                    getString(context, "shopName"),
                                    player.nameForScoreboard,
                                    context.source.registryManager
                                ).firstOrNull()
                                val tradedItemNeedDelete = getItemStackArgument(context, "item")

                                shop?.let {
                                    val action = {
                                        it.deleteTradedItem(tradedItemNeedDelete)
                                        player.sendMessage(tr("commands.shop.item.delete.success",
                                            tradedItemNeedDelete.createStack(1, false).toHoverableText())
                                        )
                                        it.delete()
                                    }
                                    addPendingOperation(context, action)
                                } ?: {
                                    player.sendMessage(tr("commands.shops.none"))
                                }
                                1
                            }
                        )
                    )
                )
                .then(literal("change")
                    .then(argument("shopName", string())
                        .then(argument("item", itemStack(registryAccess))
                            .then(argument("quantitySoldEachTime", integer(1))
                                .then(argument("price", doubleArg(0.1))
                                    .executes { context ->
                                        val player = context.source.player!!
                                        val shop = shopDBService.readByShopName(
                                            getString(context, "shopName"),
                                            player.nameForScoreboard,
                                            context.source.registryManager
                                        ).singleOrNull()

                                        shop?.let {
                                            val tradedItemNeedChange = it.getTradedItem(getItemStackArgument(context, "item"))
                                            tradedItemNeedChange?.let {
                                                it.count = getInteger(context, "quantitySoldEachTime")
                                                it.price = getDouble(context, "price")
                                                player.sendMessage(tr("commands.shop.item.change.success"))
                                            } ?: {
                                                player.sendMessage(tr("commands.shop.item.none"))
                                            }
                                            it.update()
                                        }
                                        1
                                    }
                                )
                            )
                        )
                    )
                )
            )
            .then(literal("confirm")
                .executes { context ->
                    val playerUUID = context.source.player?.uuid
                    val pendingOperation = pendingOperations.remove(playerUUID)
                    pendingOperation?.let {
                        it.operation()
                        val textSupplier = Supplier<Text> { tr("commands.confirm.ok") }
                        context.source.sendFeedback(textSupplier, false)
                    } ?: {
                        val textSupplier = Supplier<Text> { tr("commands.confirm.none") }
                        context.source.sendFeedback(textSupplier, false)
                    }
                    customScope.cancel()
                    1
                }
            )
            .then(literal("cancel")
                .executes { context ->
                    val playerUUID = context.source.player?.uuid
                    val textSupplier: Supplier<Text> =
                    pendingOperations.remove(playerUUID)?.let {
                        Supplier<Text> { tr("commands.cancel.ok") }
                    } ?: run {
                        Supplier<Text> { tr("commands.cancel.none") }
                    }
                    context.source.sendFeedback(textSupplier, false)
                    customScope.cancel()
                    1
                }
            )
    val villagerShopCommandNode = dispatcher.register(builder)
    dispatcher.register(literal("vlsp").redirect(villagerShopCommandNode))
}

private fun addPendingOperation(context: CommandContext<ServerCommandSource>, operation: () -> Unit) {
    val player = context.source.player!!
    val playerUUID = player.uuid
    if (pendingOperations.containsKey(playerUUID)) {
        context.source.sendError(tr("commands.confirm.already.have"))
    } else {
        pendingOperations[playerUUID] = PendingOperation(playerUUID, operation)
        player.sendMessage(
            Text.translatable("commands.confirm.need",
                Text.literal("/villagerShop confirm")
                    .setStyle(Style.EMPTY.withColor(Formatting.GREEN)
                        .withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/villagerShop confirm"))
                        .withUnderline(true)
                    )
            )
        )
        coroutineScope(context)
    }
}

private fun suggestOptions(builder: SuggestionsBuilder, options: List<String>): CompletableFuture<Suggestions> {
    options.forEach { option ->
        builder.suggest(option)
    }
    return builder.buildFuture()
}