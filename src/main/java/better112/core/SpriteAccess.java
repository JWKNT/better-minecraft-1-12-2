package better112.core;

/** Added to TextureAtlasSprite by the patcher: the last frame the sprite was drawn. */
public interface SpriteAccess {

    int better112_getLastUse();

    void better112_setLastUse(int frame);
}
