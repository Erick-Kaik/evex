package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.abi.AbiSource
import br.com.bbchain.evex.indexer.domain.abi.AbiStorageType
import br.com.bbchain.evex.indexer.domain.abi.ContractAbi
import br.com.bbchain.evex.indexer.domain.abi.StorageLocation
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase

/**
 * Integration test against a real Postgres (Testcontainers, DEC-009) — exercises
 * [JpaContractAbiAdapter] through the Spring Data repository it wraps. Same pattern as
 * [JpaMonitoredContractAdapterTest].
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaContractAbiAdapterTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var repository: ContractAbiJpaRepository

    private val adapter: JpaContractAbiAdapter by lazy { JpaContractAbiAdapter(repository) }

    @Test
    fun `save inserts a new version and findAllByContractAddress retrieves it`() {
        val address = "0x111111111111111111111111111111111111111a"
        val abi = newAbi(address, validFromBlock = 0)

        adapter.save(abi)

        val found = adapter.findAllByContractAddress(address)
        assertThat(found).hasSize(1)
        assertThat(found.first().copy(createdAt = abi.createdAt)).isEqualTo(abi)
    }

    @Test
    fun `findAllByContractAddress orders by validFromBlock ascending`() {
        val address = "0x222222222222222222222222222222222222222b"
        val v2 = newAbi(address, validFromBlock = 200)
        val v1 = newAbi(address, validFromBlock = 0, validToBlock = 199)
        adapter.save(v2)
        adapter.save(v1)

        val found = adapter.findAllByContractAddress(address)

        assertThat(found.map { it.validFromBlock }).containsExactly(0L, 200L)
    }

    @Test
    fun `findCurrentOpenVersion returns the version with validToBlock null`() {
        val address = "0x333333333333333333333333333333333333333c"
        adapter.save(newAbi(address, validFromBlock = 0, validToBlock = 99))
        val open = newAbi(address, validFromBlock = 100, validToBlock = null)
        adapter.save(open)

        val found = adapter.findCurrentOpenVersion(address)

        assertThat(found?.validFromBlock).isEqualTo(100L)
        assertThat(found?.validToBlock).isNull()
    }

    @Test
    fun `findCurrentOpenVersion returns null when every version is closed`() {
        val address = "0x444444444444444444444444444444444444444d"
        adapter.save(newAbi(address, validFromBlock = 0, validToBlock = 99))

        assertThat(adapter.findCurrentOpenVersion(address)).isNull()
    }

    @Test
    fun `save with an already-persisted id updates instead of inserting a new row`() {
        val address = "0x555555555555555555555555555555555555555e"
        val abi = newAbi(address, validFromBlock = 0)
        adapter.save(abi)

        adapter.save(abi.copy(validToBlock = 50))

        val found = adapter.findAllByContractAddress(address)
        assertThat(found).hasSize(1)
        assertThat(found.first().validToBlock).isEqualTo(50L)
    }

    @Test
    fun `findAll returns versions across every contract address`() {
        adapter.save(newAbi("0x666666666666666666666666666666666666666f", validFromBlock = 0))
        adapter.save(newAbi("0x777777777777777777777777777777777777777a", validFromBlock = 0))

        assertThat(adapter.findAll().size).isGreaterThanOrEqualTo(2)
    }

    @Test
    fun `round-trips abiContent (DATABASE storage) and storagePath (LOCAL storage) through storageLocation`() {
        val databaseAddress = "0x888888888888888888888888888888888888888b"
        val localAddress = "0x999999999999999999999999999999999999999c"
        adapter.save(newAbi(databaseAddress, validFromBlock = 0, storageType = AbiStorageType.DATABASE, location = StorageLocation(abiContent = "[]", storagePath = null)))
        adapter.save(newAbi(localAddress, validFromBlock = 0, storageType = AbiStorageType.LOCAL, location = StorageLocation(abiContent = null, storagePath = "/data/abis/x.json")))

        val fromDb = adapter.findAllByContractAddress(databaseAddress).first()
        val fromLocal = adapter.findAllByContractAddress(localAddress).first()

        assertThat(fromDb.storageLocation.abiContent).isEqualTo("[]")
        assertThat(fromDb.storageLocation.storagePath).isNull()
        assertThat(fromLocal.storageLocation.abiContent).isNull()
        assertThat(fromLocal.storageLocation.storagePath).isEqualTo("/data/abis/x.json")
    }

    @Test
    fun `implementationAddress round-trips and defaults to null`() {
        val address = "0x555555555555555555555555555555555555555e"
        val impl = "0x00000000000000000000000000000000000000a1"
        adapter.save(newAbi(address, 0, validToBlock = 9))
        adapter.save(newAbi(address, 10, implementationAddress = impl))

        val found = adapter.findAllByContractAddress(address)

        assertThat(found.map { it.implementationAddress }).containsExactly(null, impl)
    }

    @Test
    fun `deleteLegacyBlockscoutVersions removes only BLOCKSCOUT versions without implementation_address`() {
        val address = "0x666666666666666666666666666666666666666f"
        val other = "0x777777777777777777777777777777777777777a"
        val impl = "0x00000000000000000000000000000000000000a1"
        adapter.save(newAbi(address, 0, validToBlock = 9, source = AbiSource.BLOCKSCOUT)) // legado: some
        adapter.save(newAbi(address, 10, validToBlock = 19, source = AbiSource.UPLOAD_TEXT))
        adapter.save(newAbi(address, 20, validToBlock = 29, source = AbiSource.UPLOAD_FILE))
        adapter.save(newAbi(address, 30, validToBlock = 39, source = AbiSource.AUTO_DETECTED))
        adapter.save(newAbi(address, 40, source = AbiSource.BLOCKSCOUT, implementationAddress = impl))
        adapter.save(newAbi(other, 0, source = AbiSource.BLOCKSCOUT)) // outro endereço

        val removed = adapter.deleteLegacyBlockscoutVersions(address)

        assertThat(removed).isEqualTo(1)
        assertThat(adapter.findAllByContractAddress(address).map { it.validFromBlock }).containsExactly(10L, 20L, 30L, 40L)
        assertThat(adapter.findAllByContractAddress(other)).hasSize(1)
    }

    private fun newAbi(
        address: String,
        validFromBlock: Long,
        validToBlock: Long? = null,
        source: AbiSource = AbiSource.UPLOAD_TEXT,
        implementationAddress: String? = null,
        storageType: AbiStorageType = AbiStorageType.DATABASE,
        location: StorageLocation = StorageLocation(abiContent = "[]", storagePath = null),
    ) = ContractAbi(
        id = UUID.randomUUID(),
        contractAddress = address,
        network = Network.TESTNET,
        source = source,
        storageType = storageType,
        storageLocation = location,
        validFromBlock = validFromBlock,
        validToBlock = validToBlock,
        // truncado pra segundos: coluna `timestamp` do Postgres não preserva nanossegundos de Instant.now()
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
        implementationAddress = implementationAddress,
    )
}
