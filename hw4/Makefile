# Makefile to compile TCPend.java, senderSide.java, and receiverSide.java

# Compiler
JAVAC = javac

# Directory for source files
SRC_DIR = src

# Java source files
SOURCES = $(SRC_DIR)/TCPend.java $(SRC_DIR)/senderSide.java $(SRC_DIR)/receiverSide.java

# Java class files
CLASSES = $(SRC_DIR)/TCPend.class $(SRC_DIR)/senderSide.class $(SRC_DIR)/receiverSide.class

# Default rule to make everything (compile)
all: $(CLASSES)

# Rule to compile the java files if needed
$(SRC_DIR)/TCPend.class: $(SRC_DIR)/TCPend.java $(SRC_DIR)/senderSide.class $(SRC_DIR)/receiverSide.class
	$(JAVAC) -cp $(SRC_DIR) -d $(SRC_DIR) $(SRC_DIR)/TCPend.java

$(SRC_DIR)/senderSide.class: $(SRC_DIR)/senderSide.java
	$(JAVAC) -d $(SRC_DIR) $(SRC_DIR)/senderSide.java

$(SRC_DIR)/receiverSide.class: $(SRC_DIR)/receiverSide.java
	$(JAVAC) -d $(SRC_DIR) $(SRC_DIR)/receiverSide.java

# Clean up the class files
clean:
	rm -f $(SRC_DIR)/*.class
