doc "Test member functions of Float literals"
void test(Process process) {
    // Basic Float literal
    //TODO should this work?  process.writeLine(23.0.pred);
    process.writeLine(23.0.succ());

    // Float literals with magnitudes
    //TODO process.writeLine(3.0m.pred());
    //TODO process.writeLine(1.0k.pred());

    // Float literals with underscores
    //TODO process.writeLine(1_123.435.succ());

    // Float literals with underscores and magnitudes
    //TODO add some
}
