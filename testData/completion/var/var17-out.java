// Items: arg, cast, for, instanceof, not, par, var
public class Foo {
    void m() {
        int foo = 2 + 3;
        int i = foo<caret> * 4;    // blah
    }
}