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

    assertEquals(384, repository.catalog().size)

    val products = repository.search("pace runner white blue size 9", null)
    assertTrue(products.isNotEmpty())
    assertEquals("SKU-1150", products.first().sku)
    assertEquals("White / Blue", products.first().color)
    assertEquals("9", products.first().size)

    val exactVariant = repository.search("Studio Zip Hoodie size M", null)
    assertEquals(2, exactVariant.size)
    assertTrue(exactVariant.all { it.name == "Studio Zip Hoodie" })
    assertTrue(exactVariant.all { it.size == "M" })

    val locationProducts = repository.locationContents("Section A2 · Bin 2")
    assertEquals(1, locationProducts.size)
    assertTrue(locationProducts.all { it.location == "Section A2 · Bin 2" })

    assertTrue(repository.findTasks(null, null, null).isEmpty())

    val proposal =
      ToolProposal(
        P1Tool.REPORT_ISSUE,
        linkedMapOf(
          "category" to "damaged_stock",
          "status" to "OPEN",
          "priority" to "MEDIUM",
          "product_id" to "studio-hoodie-black-m",
          "sku" to "SKU-1017",
          "quantity" to "3",
          "location" to "Section A2 · Bin 2",
        ),
      )
    val issue = repository.createIssue(proposal, "Report three damaged black Studio Zip Hoodies size medium in Section A2 Bin 2")
    val stored = repository.getIssue(issue.issueId)

    assertNotNull(stored)
    assertTrue(issue.issueId.startsWith("ISS-"))
    assertEquals("OPEN", stored?.status)
    assertEquals("MEDIUM", stored?.priority)
    assertEquals("3 Studio Zip Hoodie Black M damaged at Section A2 · Bin 2", stored?.description)
    assertTrue(stored?.payloadJson.orEmpty().contains("report_issue"))

    val replenishment =
      repository.createReplenishment(
        ToolProposal(
          P1Tool.REQUEST_REPLENISHMENT,
          linkedMapOf(
            "product_id" to "studio-hoodie-black-m",
            "sku" to "SKU-1017",
            "quantity" to "12",
            "units_to_move" to "4",
            "quantity_mode" to "target_level",
            "destination_location" to "Section A2 · Bin 2",
            "reason" to "Restore target stock",
          ),
        ),
        "Replenish black Studio Zip Hoodies size medium to 12 at Section A2 Bin 2",
      )
    val storedReplenishment = repository.getReplenishment(replenishment.requestId)
    assertNotNull(storedReplenishment)
    assertEquals(4, storedReplenishment?.unitsToMove)
    assertEquals("REQUESTED", storedReplenishment?.status)
    assertTrue(storedReplenishment?.payloadJson.orEmpty().contains("request_replenishment"))
  }
}
