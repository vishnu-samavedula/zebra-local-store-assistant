package com.example.zebralocalai.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteWarehouseRepositoryDeviceTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val databaseName = "warehouse-repository-device-test.db"

  @Before fun resetBefore() { context.deleteDatabase(databaseName) }
  @After fun resetAfter() { context.deleteDatabase(databaseName) }

  @Test
  fun seededCatalogAndIssueRoundTrip() {
    val repository = SqliteWarehouseRepository(context, databaseName)

    assertEquals(49, repository.catalog().size)

    val products = repository.search("blue trailblaze shoes size 10.5", null)
    assertTrue(products.isNotEmpty())
    assertEquals("SKU-1842-BLU-105", products.first().sku)
    assertEquals("Blue", products.first().color)
    assertEquals("10.5", products.first().size)

    val exactVariant = repository.search("Summit Oxford shirt size M", null)
    assertEquals(2, exactVariant.size)
    assertTrue(exactVariant.all { it.name == "Summit Oxford Shirt" })
    assertTrue(exactVariant.all { it.size == "M" })

    val locationProducts = repository.locationContents("B2-01")
    assertEquals(2, locationProducts.size)
    assertTrue(locationProducts.all { it.location == "B2-01" })

    val task = repository.findTasks("T-104", null, null).single()
    assertEquals("in_progress", task.status)
    assertEquals("SKU-1843-BLK-100", task.sku)

    val proposal =
      ToolProposal(
        P1Tool.REPORT_ISSUE,
        linkedMapOf(
          "description" to "3 damaged stock unit(s) for Titan Shipping Carton at B7",
          "category" to "damaged_stock",
          "status" to "OPEN",
          "priority" to "MEDIUM",
          "product_id" to "PROD-TITAN-CARTON-STD",
          "sku" to "SKU-5520-STD",
          "quantity" to "3",
          "location" to "B7",
        ),
      )
    val issue = repository.createIssue(proposal, "Report three damaged cartons of SKU 5520 in B7")
    val stored = repository.getIssue(issue.issueId)

    assertNotNull(stored)
    assertTrue(issue.issueId.startsWith("ISS-"))
    assertEquals("OPEN", stored?.status)
    assertEquals("MEDIUM", stored?.priority)
    assertTrue(stored?.payloadJson.orEmpty().contains("report_issue"))

    val replenishment =
      repository.createReplenishment(
        ToolProposal(
          P1Tool.REQUEST_REPLENISHMENT,
          linkedMapOf(
            "product_id" to "PROD-TRAILBLAZE-GTX-BLK-100",
            "sku" to "SKU-1843-BLK-100",
            "quantity" to "12",
            "units_to_move" to "4",
            "quantity_mode" to "target_level",
            "destination_location" to "A3-05",
            "reason" to "Restore target stock",
          ),
        ),
        "Replenish black GTX to 12 at A3-05",
      )
    val storedReplenishment = repository.getReplenishment(replenishment.requestId)
    assertNotNull(storedReplenishment)
    assertEquals(4, storedReplenishment?.unitsToMove)
    assertEquals("REQUESTED", storedReplenishment?.status)
    assertTrue(storedReplenishment?.payloadJson.orEmpty().contains("request_replenishment"))
  }
}
