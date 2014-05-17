package com.anjlab.eclipse.tapestry5;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMemberValuePair;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.Name;

public abstract class TapestryContext
{
    public static interface FileNameBuilder
    {
        String getFileName(String fileName, String fileExtension);
    }

    private List<TapestryFile> files;
    
    private TapestryFile initialFile;
    
    public static TapestryContext emptyContext()
    {
        return new TapestryContext()
        {
            @Override
            protected Map<String, String> codeDesignExtensionMappings()
            {
                return null;
            }
            
            @Override
            public String getPackageName()
            {
                return null;
            }
            
            @Override
            protected ICompilationUnit getCompilationUnit()
            {
                return null;
            }
            
            @Override
            public List<TapestryFile> findTapestryFiles(TapestryFile forFile,
                    boolean findFirst, FileNameBuilder fileNameBuilder)
            {
                return Collections.emptyList();
            }
            
            @Override
            public boolean isReadOnly()
            {
                return true;
            }
            
            @Override
            public TapestryComponentSpecification getSpecification()
            {
                return TapestryComponentSpecification.EMPTY;
            }
            
            @Override
            public IType getJavaType()
            {
                return null;
            }
        };
    }
    
    public TapestryContext()
    {
        this.files = new ArrayList<TapestryFile>();
    }

    protected void initFromFile(TapestryFile file)
    {
        if (this != file.getContext())
        {
            throw new IllegalStateException("File is not from this context");
        }
        
        if (initialFile != null)
        {
            throw new IllegalStateException("Already initialized");
        }
        
        initialFile = file;
        
        if (file.isJavaFile() || file.isTemplateFile())
        {
            initFromJavaOrTemplateFile(file);
        }
        else if (file.isPropertiesFile())
        {
            initFromPropertiesFile(file);
        }
        else if (file.isJavaScriptFile() || file.isStyleSheetFile())
        {
            initFromImportedFile(file);
        }
    }
    
    private void addImports()
    {
        ICompilationUnit compilationUnit = null;
        
        try
        {
            compilationUnit = getCompilationUnit();
            
            if (compilationUnit == null)
            {
                return;
            }
            
            AST ast = null;
            boolean astParsed = false;
            
            for (IType type : compilationUnit.getAllTypes())
            {
                for (IAnnotation annotation : type.getAnnotations())
                {
                    if (TapestryUtils.isTapestryImportAnnotation(annotation))
                    {
                        IMemberValuePair[] pairs = annotation.getMemberValuePairs();
                        for (IMemberValuePair pair : pairs)
                        {
                            if ("library".equals(pair.getMemberName())
                                    || "stylesheet".equals(pair.getMemberName())
                                    || "stack".equals(pair.getMemberName()))
                            {
                                if (!astParsed)
                                {
                                    astParsed = true;
                                    try
                                    {
                                        ast = EclipseUtils.parse(compilationUnit).getAST();
                                    }
                                    catch (Exception e)
                                    {
                                        //  Ignore
                                    }
                                }
                                
                                processImport(annotation, pair.getMemberName(), pair.getValue(), pair.getValueKind(), ast);
                            }
                        }
                    }
                }
            }
        }
        catch (JavaModelException e)
        {
            Activator.getDefault().logError("Error inspecting compilation unit", e);
        }
        finally
        {
            if (compilationUnit != null)
            {
                dispose(compilationUnit);
            }
        }
    }
    
    protected void dispose(ICompilationUnit compilationUnit)
    {
        //  Default implementation does nothing
    }

    protected abstract ICompilationUnit getCompilationUnit();
    
    private void processImport(IAnnotation annotation, String type, Object value, int valueKind, AST ast)
    {
        if (value instanceof Object[])
        {
            for (Object item : (Object[])value)
            {
                processImportedFile(annotation, type, eval(item, valueKind, ast));
            }
        }
        else if (value instanceof String)
        {
            processImportedFile(annotation, type, eval(value, valueKind, ast));
        }
    }

    private String eval(Object value, int valueKind, AST ast)
    {
        if (ast != null
                && (valueKind == IMemberValuePair.K_SIMPLE_NAME
                    || valueKind == IMemberValuePair.K_QUALIFIED_NAME))
        {
            Name name = ast.newName((String) value);
            value = EclipseUtils.evalExpression(getProject(), name);
        }
        return (String) value;
    }
    
    private void processImportedFile(IAnnotation annotation, String type, String fileName)
    {
        ISourceRange sourceRange = null;
        try
        {
            sourceRange = annotation.getSourceRange();
        }
        catch (JavaModelException e)
        {
            Activator.getDefault().logError("Error getting annotation location", e);
        }
        
        if ("stack".equals(type))
        {
            files.add(new JavaScriptStackReference(getJavaFile(), fileName, sourceRange));
        }
        else
        {
            files.add(new AssetReference(getJavaFile(), sourceRange, fileName));
        }
    }
    
    public List<TapestryFile> getFiles()
    {
        return Collections.unmodifiableList(files);
    }
    
    public TapestryFile getJavaFile()
    {
        for (TapestryFile file : files)
        {
            if (file.isJavaFile())
            {
                return file;
            }
        }
        return null;
    }
    
    public TapestryFile getTemplateFile()
    {
        for (TapestryFile file : files)
        {
            if (file.isTemplateFile())
            {
                return file;
            }
        }
        return null;
    }
    
    private void initFromImportedFile(TapestryFile file)
    {
        List<TapestryFile> files = findTapestryFiles(file, true, new TapestryContext.FileNameBuilder()
        {
            @Override
            public String getFileName(String fileName, String fileExtension)
            {
                return fileName.substring(0, fileName.lastIndexOf(fileExtension))
                        + codeDesignExtensionMappings().get("tml");
            }
        });
        
        if (files.isEmpty())
        {
            //  Support alternative naming of the asset files: lower-case-with-dashes
            files = findTapestryFiles(file, true, new TapestryContext.FileNameBuilder()
            {
                @Override
                public String getFileName(String fileName, String fileExtension)
                {
                    StringBuilder builder = new StringBuilder();
                    String[] pathParts = fileName.split("/");
                    for (int i = 0; i < pathParts.length - 1; i++)
                    {
                        builder.append(pathParts[i]).append("/");
                    }
                    
                    String[] parts = pathParts[pathParts.length - 1].split("-");
                    for (String part : parts)
                    {
                        builder.append(Character.toUpperCase(part.charAt(0)))
                               .append(part.substring(1));
                    }
                    fileName = builder.toString();
                    return fileName.substring(0, fileName.lastIndexOf(fileExtension))
                            + codeDesignExtensionMappings().get("tml");
                }
            });
        }
        
        if (!files.isEmpty())
        {
            TapestryFile javaFile = files.get(0);
            addWithComplementFile(javaFile);
            addImports();
            
            if (!contains(file))
            {
                //  Assumption was wrong: Original file not from this context
                this.files.clear();
                
                this.files.add(file);
            }
        }
    }
    
    private void initFromPropertiesFile(TapestryFile file)
    {
        List<TapestryFile> files = findTapestryFiles(file, true, new TapestryContext.FileNameBuilder()
        {
            @Override
            public String getFileName(String fileName, String fileExtension)
            {
                Matcher matcher = getLocalizedPropertiesPattern().matcher(fileName);
                String codeExtension = codeDesignExtensionMappings().get("tml");
                if (matcher.find())
                {
                    return matcher.group(1) + "." + codeExtension;
                }
                return fileName.substring(0, fileName.lastIndexOf(fileExtension)) + codeExtension;
            }

        });
        
        if (!files.isEmpty())
        {
            TapestryFile javaFile = files.get(0);
            addWithComplementFile(javaFile);
        }
        
        addPropertiesFiles(file);
        addImports();
    }
    
    private void initFromJavaOrTemplateFile(TapestryFile file)
    {
        addWithComplementFile(file);
        addPropertiesFiles(file);
        addImports();
    }
    
    private void addWithComplementFile(TapestryFile file)
    {
        this.files.add(file);
        TapestryFile complementFile = findComplementFile(file);
        if (complementFile != null)
        {
            if (complementFile.isJavaFile())
            {
                //  Keep Java file on top of the list
                this.files.add(0, complementFile);
            }
            else
            {
                this.files.add(complementFile);
            }
        }
    }
    
    private void addPropertiesFiles(TapestryFile file)
    {
        List<TapestryFile> propertiesFiles = findTapestryFiles(file, false, new TapestryContext.FileNameBuilder()
        {
            @Override
            public String getFileName(String fileName, String fileExtension)
            {
                Matcher matcher = getLocalizedPropertiesPattern().matcher(fileName);
                
                String propertiesSuffix = "(|_.*)\\.properties";
                
                if (matcher.find())
                {
                    return matcher.group(1) + propertiesSuffix;
                }
                return fileName.substring(0, fileName.lastIndexOf(fileExtension) - 1) + propertiesSuffix;
            }
        });
        
        for (TapestryFile properties : propertiesFiles)
        {
            this.files.add(properties);
        }
    }
    
    private Pattern getLocalizedPropertiesPattern()
    {
        return Pattern.compile("([^_]*)(_.*)+\\.properties");
    }
    
    public boolean contains(IFile file)
    {
        return contains(new LocalFile(this, file));
    }
    
    public boolean contains(TapestryFile file)
    {
        if (file == null)
        {
            return false;
        }
        
        for (TapestryFile f : files)
        {
            if (f.equals(file))
            {
                return true;
            }
        }
        return false;
    }
    
    public void validate()
    {
        for (TapestryFile file : files)
        {
            if (file instanceof TapestryFileReference)
            {
                try
                {
                    ((TapestryFileReference) file).resolveFile(true);
                }
                catch (UnresolvableReferenceException e)
                {
                    //  Ignore
                }
            }
        }
    }
    
    public static void deleteMarkers(IResource project)
    {
        try
        {
            IMarker[] markers = project.findMarkers(IMarker.PROBLEM, false, IResource.DEPTH_INFINITE);
            
            for (IMarker marker : markers)
            {
                //  TODO Support other markers too
                if (marker.getAttribute(AssetReference.MARKER_NAME) != null
                        || marker.getAttribute(JavaScriptStackReference.MARKER_NAME) != null)
                {
                    marker.delete();
                }
            }
        }
        catch (CoreException e)
        {
            Activator.getDefault().logError("Error deleting asset problem markers", e);
        }
    }
    
    public IProject getProject()
    {
        for (TapestryFile file : files)
        {
            return file.getProject();
        }
        return null;
    }
    
    public boolean contains(String fileName)
    {
        for (TapestryFile file : files)
        {
            if (fileName.equals(file.getName()))
            {
                return true;
            }
        }
        return false;
    }
    
    public String getName()
    {
        for (TapestryFile file : files)
        {
            if (file.isJavaFile() || file.isTemplateFile())
            {
                return file.getName().substring(0,
                        file.getName().length() - file.getFileExtension().length() - 1);
            }
        }
        return null;
    }
    
    public boolean isEmpty()
    {
        return files.isEmpty();
    }
    
    public abstract String getPackageName();
    
    public void remove(IFile file)
    {
        remove(new LocalFile(this, file));
    }
    
    public void remove(TapestryFile file)
    {
        if (this.files.remove(file) && file.isJavaFile())
        {
            //  Remove all @Imports, because Java file removed
            //  and assets could be only traversed from the Java file
            
            Iterator<TapestryFile> iterator = files.iterator();
            
            while (iterator.hasNext())
            {
                TapestryFile f = iterator.next();
                
                if (!f.isTemplateFile() && !f.isPropertiesFile())
                {
                    iterator.remove();
                }
            }
        }
    }

    public abstract List<TapestryFile> findTapestryFiles(TapestryFile forFile, boolean findFirst, FileNameBuilder fileNameBuilder);

    public TapestryFile findComplementFile(TapestryFile file)
    {
        List<TapestryFile> files = findTapestryFiles(file, true, new TapestryContext.FileNameBuilder()
        {
            @Override
            public String getFileName(String fileName, String fileExtension)
            {
                String complementExtension = codeDesignExtensionMappings().get(fileExtension);
                
                if (complementExtension == null)
                {
                    throw new IllegalArgumentException();
                }
                
                return fileName.substring(0, fileName.lastIndexOf(fileExtension)) + complementExtension;
            }
        });
        
        return !files.isEmpty() ? files.get(0) : null;
    }

    protected abstract Map<String, String> codeDesignExtensionMappings();

    public TapestryFile getInitialFile()
    {
        return initialFile;
    }

    public abstract boolean isReadOnly();
    
    public abstract TapestryComponentSpecification getSpecification();
    
    public abstract IType getJavaType();
}