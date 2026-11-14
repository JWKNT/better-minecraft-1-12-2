package better112.core;

/** Added to World by the patcher: the chunk the server thread asked this world for last (a Chunk, or null). */
public interface ChunkKeeper {

    Object better112_keptChunk();

    void better112_keepChunk(Object chunk);
}
