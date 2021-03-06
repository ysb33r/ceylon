import check { check, fail }

dynamic DataHolder {
  shared formal dynamic data;
}

//See #362
dynamic Node satisfies DataHolder {
  shared formal Array<Node> children;
}

Node createNode(Node? k2) {
  dynamic {
    dynamic k1 = dynamic[data="A child.";children=[];];
    dynamic r = dynamic[
      data=dynamic[a=1;b="2";];
      children=dynamic[k1];
    ];
    if (k2 exists) {
      r.children.push(k2);
    }
    return r;
  }
}

//See #616
dynamic JsStr {
  shared formal Integer length;
}
T test616<T>(Boolean flag)
    given T satisfies JsStr {
  if (flag) {
    //Test for scope here
    dynamic {
      return eval("new String()");
    }
  }
  dynamic {
    return eval("new String()");
  }
}

void testDynamicInterfaces() {
  Object o = createNode(null);
  if (is Node n=o) {
    check(true, "Dynamic interfaces #1");
    check(n.children.size==1, "Dynamic interfaces #2");
    //TODO should we really expect n.children[0] to be a Node?
    if (exists k=n.children[0]) {
      check(true, "Dynamic interfaces #3");
      check(k.children.size==0, "Dynamic interfaces #4");
      dynamic {
        check(k.data=="A child.", "Dynamic interfaces #5");
      }
    } else {
      fail("Dynamic interfaces #3");
    }
    check(!n.children[1] exists, "Dynamic interfaces #6");
    check(n.string == "[object Object]", "string attribute");
    check(n.hash > 0, "hash attribute");
  } else {
    fail("Dynamic interfaces #1");
  }
  check((test616<JsStr>(true) of Object) is JsStr, "#616.1");
  check((test616<JsStr>(false) of Object) is JsStr, "#616.2");
  
  testNarrowDynamicInterfaces();
}

dynamic HasA {
    shared formal String a;
}
dynamic HasAB satisfies HasA {
    shared formal String b;
}
dynamic HasABC satisfies HasAB {
    shared formal String c;
}
dynamic OtherHasABC {
    shared formal String a;
    shared formal String b;
    shared formal String c;
}

void testNarrowDynamicInterfaces() {
    HasA abc;
    dynamic { abc = dynamic [ a = "a"; b = "b"; c = "c"; ]; }
    check(abc is HasA, "object has its static type");
    check(abc is HasAB, "narrow type");
    check(abc is HasABC, "narrow type 2");
    check(abc is HasA, "object still has its static type");
    check(!abc is OtherHasABC, "don't narrow to unrelated type");
    check(abc is HasA, "object still has its static type 2");
    HasA ab;
    dynamic { ab = dynamic [ a = "a"; b = "b"; ]; }
    check(ab is HasA, "second object has its static type");
    check(ab is HasAB, "narrow type 3");
    check(!ab is HasABC, "don't narrow to inappropriate type");
    check(!ab is OtherHasABC, "don't narrow to inappropriate and unrelated type");
}
