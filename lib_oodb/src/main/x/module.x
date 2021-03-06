/**
 * This module contains the Object Oriented Database (OODB) APIs.
 */
module oodb.xtclang.org
    {
    typedef (DBInvoke<<>, <Boolean>> | function Boolean()) Condition;

    /**
     * This mixin is used to mark a module as being a database module.
     */
    mixin Database
            into Module
        {
        }

    /**
     * Marks specific DBObjects as extra-transactional. Applies only to DBCounter and DBLog.
     */
    mixin NoTx
            into DBObject
        {
        assert()
            {
            // verify that the mixin is into a DBSchema (redundant), a DBValue, or a DBLog;
            // the instantiation of the "real this" has not yet occurred, so at this point,
            // "this" refers to the structure
            // REVIEW GG
            assert this.is(DBSchema:struct)
                || this.is(DBCounter:struct)
                || this.is(DBLog:struct);
            }

        @Override
        @RO Boolean transactional.get()
            {
            return False;
            }
        }

    /**
     * Check to see if any of the database object name rules is broken by the specified name, and
     * if so, provide an explanation of the broken rule.
     *
     * @param name  the name to check
     *
     * @return True iff the name violates the database object name rules
     * @return (conditional) a description of how the name broke the naming rules
     */
    static conditional String isInvalidName(String name)
        {
        import ecstasy.lang.src.Lexer;
        import Lexer.isIdentifierStart;
        import Lexer.isIdentifierPart;
        import Lexer.Id.allKeywords;

        if (name == "")
            {
            return True, "The name is blank";
            }

        if (allKeywords.contains(name))
            {
            return True, $"Name ({name.quoted()}) is an Ecstasy keyword";
            }

        for (Char ch : name)
            {
            // explicitly call out characters that are reserved for filing system use
            switch (ch)
                {
                case '.':
                    return True, $"The name ({name.quoted()}) contains a period ('.')";
                case ' ':
                    return True, $"The name ({name.quoted()}) contains a space (' ')";
                case ':':
                    return True, $"The name ({name.quoted()}) contains a colon (':')";
                case '\\':
                    return True, $"The name ({name.quoted()}) contains a back-slash ('\\')";
                case '/':
                    return True, $"The name ({name.quoted()}) contains a forward-slash ('/')";
                }

            if (!isIdentifierPart(ch))
                {
                return True, $|Name ({name.quoted()}) must not contain the character {ch.quoted()}\
                              | ({ch.category.description})
                             ;
                }
            }

        if (!isIdentifierStart(name[0]))
            {
            return True, $"The name ({name.quoted()}) does not begin with a letter or an underscore";
            }

        // REVIEW compiler message when accidentally including a unary prefix '!': if (!name[name.size-1] == '_')
        if (name[name.size-1] == '_')
            {
            return True, $"The name ({name.quoted()}) ends with an underscore";
            }

        return False;
        }
    }
