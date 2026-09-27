package io.github.jacek4yang.stegsolver.ui;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import io.github.jacek4yang.stegsolver.TestImages;
class DocumentSessionTest {
    @Test void installsAlreadyDecodedPixelsWithoutReadingFileAgain() {
        var session = new DocumentSession();
        var image = TestImages.randomRgb(3, 2, 1);
        session.acceptLoaded(Path.of("file-that-no-longer-exists.png"), image);
        assertSame(image, session.image());
        assertEquals(0, session.transformIndex());
        session.setTransformIndex(17);
        session.openFromImage(image, "frame");
        assertEquals(0, session.transformIndex());
        assertNull(session.path());
    }
}
