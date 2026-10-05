import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TessdataModelsTest {
    private val eng = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
    private val nld = "ced0e5e046a84c908a6aa7accbef9a232c4a5d9a8276691b81c6ee64d02963f6"

    @Test
    fun readsTheModelsInOrderAndSkipsComments() {
        val models = TessdataModels.parse("# models\n\nnld $nld  # Dutch\neng   $eng\n")

        assertEquals(listOf("nld" to nld, "eng" to eng), models.toList())
    }

    @Test
    fun refusesALineThatIsNotACodeAndAHash() {
        assertFailsWith<IllegalArgumentException> { TessdataModels.parse("eng\n") }
        assertFailsWith<IllegalArgumentException> { TessdataModels.parse("eng ${eng.take(10)}\n") }
    }

    @Test
    fun refusesALanguageListedTwice() {
        assertFailsWith<IllegalArgumentException> { TessdataModels.parse("eng $eng\neng $eng\n") }
    }
}
