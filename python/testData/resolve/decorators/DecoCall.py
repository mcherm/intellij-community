def deco(fun):
  return fun

@<caret>deco
def foo():
  pass

# same as in callee test
