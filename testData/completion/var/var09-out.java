// Items: arg, cast, for, instanceof, not, par, var
public class Foo {
    void m(boolean b) {
        boolean foo = b && false;
        m(foo<caret>);
    }
}